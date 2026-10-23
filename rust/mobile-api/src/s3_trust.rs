//! Offline S3 genesis-chain verifier. No network I/O, credentials, File Gate,
//! baseline changes or remote trust grant.
//! The network caller MUST independently verify an unchanged S3 head and ETag
//! before and after every bounded read, plus owner authorization and rollback.

use std::str::FromStr;
use aquilum_core::sync::{
    S3HeadPointerV1, S3Keyspace, S3ManifestV1, VaultId,
};

const MAX_ANCESTORS: usize = 128;
const MAX_MANIFEST_BYTES: usize = 512 * 1024;
const MAX_CHAIN_BYTES: usize = 8 * 1024 * 1024;

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum S3ChainError {
    InvalidScope, InvalidGenesis, MissingAncestry, TooManyAncestors,
    Oversized, InvalidHead, NonCanonical, InvalidManifest,
    DigestMismatch, BrokenLink, ExtraAncestry, GenesisMismatch,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct UntrustedS3ChainPreview {
    pub cloud_vault_id: String,
    pub latest_generation: u64,
    pub latest_revisions: u32,
    pub immutable_manifests_checked: usize,
    pub claimed_genesis_fingerprint: String,
}

/// Complete immutable chain newest->genesis. This inspects bytes only; it does
/// NOT prove those bytes were fetched from the expected S3 account.
pub fn check_untrusted_genesis_chain(
    prefix: &str,
    cloud_vault_id: &str,
    claimed_genesis: &str,
    head_bytes: &[u8],
    manifest_chain: &[&[u8]],
) -> Result<UntrustedS3ChainPreview, S3ChainError> {
    if claimed_genesis.len() != 64 || !claimed_genesis.bytes()
        .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
    {
        return Err(S3ChainError::InvalidGenesis);
    }
    let vault = VaultId::from_str(cloud_vault_id)
        .map_err(|_| S3ChainError::InvalidScope)?;
    let ns = S3Keyspace::new(prefix, vault)
        .map_err(|_| S3ChainError::InvalidScope)?;
    if head_bytes.len() > 4096 { return Err(S3ChainError::Oversized); }
    let head = S3HeadPointerV1::decode(head_bytes, &ns)
        .map_err(|_| S3ChainError::InvalidHead)?;
    if head.encode(&ns).map_err(|_| S3ChainError::InvalidHead)? != head_bytes {
        return Err(S3ChainError::NonCanonical);
    }
    if manifest_chain.is_empty() { return Err(S3ChainError::MissingAncestry); }
    if manifest_chain.len() > MAX_ANCESTORS {
        return Err(S3ChainError::TooManyAncestors);
    }
    let mut total = 0usize;
    for bytes in manifest_chain {
        total = total.checked_add(bytes.len()).ok_or(S3ChainError::Oversized)?;
        if bytes.len() > MAX_MANIFEST_BYTES || total > MAX_CHAIN_BYTES {
            return Err(S3ChainError::Oversized);
        }
    }

    let mut expected_id = head.manifest_id();
    let mut expected_digest = head.manifest_blake3().to_owned();
    let mut successor: Option<S3ManifestV1> = None;
    let mut latest_revisions = None;
    for (index, bytes) in manifest_chain.iter().enumerate() {
        let manifest = S3ManifestV1::decode(&ns, expected_id, bytes)
            .map_err(|_| S3ChainError::InvalidManifest)?;
        if manifest.encode(&ns).map_err(|_| S3ChainError::InvalidManifest)? != *bytes {
            return Err(S3ChainError::NonCanonical);
        }
        let digest = manifest.digest(&ns)
            .map_err(|_| S3ChainError::InvalidManifest)?;
        if digest != expected_digest {
            return Err(S3ChainError::DigestMismatch);
        }
        if let Some(ref child) = successor {
            if !child.is_successor_of(&manifest, &digest) {
                return Err(S3ChainError::BrokenLink);
            }
        } else {
            if manifest.generation != head.generation()
                || manifest.previous_manifest_id != head.previous_manifest_id()
            {
                return Err(S3ChainError::BrokenLink);
            }
            latest_revisions = Some(manifest.total_revisions);
        }
        if manifest.previous_manifest_id.is_none() {
            if index + 1 != manifest_chain.len() {
                return Err(S3ChainError::ExtraAncestry);
            }
            if manifest.generation != 1 { return Err(S3ChainError::BrokenLink); }
            // The desktop fingerprint is BLAKE3 of the canonical generation-1
            // HEAD POINTER bytes, not of the manifest bytes or provider ETag.
            let root = S3HeadPointerV1::new(
                &ns, 1, manifest.manifest_id, &digest, None,
            ).map_err(|_| S3ChainError::InvalidHead)?;
            if blake3::hash(&root.encode(&ns)
                .map_err(|_| S3ChainError::InvalidHead)?).to_hex().as_str()
                != claimed_genesis
            {
                return Err(S3ChainError::GenesisMismatch);
            }
            return Ok(UntrustedS3ChainPreview {
                cloud_vault_id: cloud_vault_id.to_owned(),
                latest_generation: head.generation(),
                latest_revisions: latest_revisions.unwrap_or(0),
                immutable_manifests_checked: index + 1,
                claimed_genesis_fingerprint: claimed_genesis.to_owned(),
            });
        }
        expected_id = manifest.previous_manifest_id.ok_or(S3ChainError::BrokenLink)?;
        expected_digest = manifest.previous_manifest_blake3.clone()
            .ok_or(S3ChainError::BrokenLink)?;
        successor = Some(manifest);
    }
    Err(S3ChainError::MissingAncestry)
}

#[cfg(test)]
mod tests {
    use super::*;
    use aquilum_core::sync::OperationId;

    const VAULT: &str = "11111111-2222-4333-8444-555555555555";
    const PREFIX: &str = "akula-sync/pair";
    const GEN: &str = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
    const NEW: &str = "bbbbbbbb-cccc-4ddd-8eee-ffffffffffff";

    struct Sample {
        ns: S3Keyspace, head: Vec<u8>, newer: Vec<u8>,
        genesis: Vec<u8>, fingerprint: String,
    }
    fn sample() -> Sample {
        let ns = S3Keyspace::new(PREFIX, VaultId::from_str(VAULT).unwrap()).unwrap();
        let first = OperationId::from_str(GEN).unwrap();
        let second = OperationId::from_str(NEW).unwrap();
        let a = S3ManifestV1::new(&ns, first, 1, None, vec![]).unwrap();
        let hash_a = a.digest(&ns).unwrap();
        let initial = S3HeadPointerV1::new(&ns, 1, first, &hash_a, None).unwrap();
        let pin = blake3::hash(&initial.encode(&ns).unwrap()).to_hex().to_string();
        let b = S3ManifestV1::new(&ns, second, 2, Some((first, hash_a)), vec![]).unwrap();
        let current = S3HeadPointerV1::new(
            &ns, 2, second, &b.digest(&ns).unwrap(), Some(first),
        ).unwrap();
        let head = current.encode(&ns).unwrap();
        Sample {
            head, newer: b.encode(&ns).unwrap(),
            genesis: a.encode(&ns).unwrap(), fingerprint: pin, ns,
        }
    }
    fn inspect(s: &Sample) -> Result<UntrustedS3ChainPreview, S3ChainError> {
        check_untrusted_genesis_chain(
            PREFIX, VAULT, &s.fingerprint, &s.head,
            &[&s.newer, &s.genesis],
        )
    }

    #[test]
    fn two_generations_match_desktop_genesis_algorithm() {
        let s = sample();
        let p = inspect(&s).unwrap();
        assert_eq!(p.latest_generation, 2);
        assert_eq!(p.latest_revisions, 0);
        assert_eq!(p.immutable_manifests_checked, 2);
    }

    #[test]
    fn invalid_scope_foreign_vault_or_fake_genesis_is_rejected() {
        let s = sample();
        assert_eq!(check_untrusted_genesis_chain(
            PREFIX, VAULT, &"0".repeat(64), &s.head, &[&s.newer, &s.genesis],
        ), Err(S3ChainError::GenesisMismatch));
        assert_eq!(check_untrusted_genesis_chain(
            "../danger", VAULT, &s.fingerprint, &s.head, &[&s.newer, &s.genesis],
        ), Err(S3ChainError::InvalidScope));
        assert!(check_untrusted_genesis_chain(
            PREFIX, "22222222-2222-4333-8444-555555555555",
            &s.fingerprint, &s.head, &[&s.newer, &s.genesis],
        ).is_err());
    }

    #[test]
    fn missing_extra_swapped_and_corrupt_ancestors_fail_closed() {
        let s = sample();
        assert_eq!(check_untrusted_genesis_chain(
            PREFIX, VAULT, &s.fingerprint, &s.head, &[&s.newer],
        ), Err(S3ChainError::MissingAncestry));
        assert_eq!(check_untrusted_genesis_chain(
            PREFIX, VAULT, &s.fingerprint, &s.head,
            &[&s.newer, &s.genesis, &s.genesis],
        ), Err(S3ChainError::ExtraAncestry));
        assert!(check_untrusted_genesis_chain(
            PREFIX, VAULT, &s.fingerprint, &s.head, &[&s.genesis, &s.newer],
        ).is_err());
        let mut tampered = s.genesis.clone();
        *tampered.last_mut().unwrap() ^= 1;
        assert!(check_untrusted_genesis_chain(
            PREFIX, VAULT, &s.fingerprint, &s.head, &[&s.newer, &tampered],
        ).is_err());
        let mut uncanonical = s.newer.clone();
        uncanonical.push(b' ');
        assert_eq!(check_untrusted_genesis_chain(
            PREFIX, VAULT, &s.fingerprint, &s.head, &[&uncanonical, &s.genesis],
        ), Err(S3ChainError::NonCanonical));
    }

    #[test]
    fn oversized_and_generation_fork_are_not_accepted() {
        let s = sample();
        assert_eq!(check_untrusted_genesis_chain(
            PREFIX, VAULT, &s.fingerprint, &s.head,
            &[&vec![0; MAX_MANIFEST_BYTES + 1]],
        ), Err(S3ChainError::Oversized));
        assert_eq!(check_untrusted_genesis_chain(
            PREFIX, VAULT, &s.fingerprint, &vec![0; 4097], &[&s.newer],
        ), Err(S3ChainError::Oversized));
        let first = OperationId::from_str(GEN).unwrap();
        let second = OperationId::from_str(NEW).unwrap();
        let gen_manifest = S3ManifestV1::decode(&s.ns, first, &s.genesis).unwrap();
        let fork = S3ManifestV1::new(
            &s.ns, second, 3, Some((first, gen_manifest.digest(&s.ns).unwrap())),
            vec![],
        ).unwrap();
        let forkhead = S3HeadPointerV1::new(
            &s.ns, 3, second, &fork.digest(&s.ns).unwrap(), Some(first),
        ).unwrap().encode(&s.ns).unwrap();
        assert_eq!(check_untrusted_genesis_chain(
            PREFIX, VAULT, &s.fingerprint, &forkhead,
            &[&fork.encode(&s.ns).unwrap(), &s.genesis],
        ), Err(S3ChainError::BrokenLink));
    }
}
