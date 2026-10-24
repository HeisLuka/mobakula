//! S4d2a: scope-bound local S3 head checkpoint verification (TOFU).
//! The remote server remains UNTRUSTED; checking a locally observed anchor
//! never verifies the publisher, grants writes, or prevents rollback of the
//! device's own encrypted checkpoint file by a privileged local attacker.

use std::str::FromStr;
use serde::{Serialize, Deserialize};
use aquilum_core::sync::{
    OperationId, S3HeadPointerV1, S3Keyspace, S3ManifestV1, VaultId,
};
use crate::s3_trust::check_untrusted_genesis_chain;

const SCHEMA: u8 = 1;
const MAX_ANCHOR_BYTES: usize = 512;

#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct S3ObservedAnchorV1 {
    schema: u8,
    pub scope_fingerprint: String,
    pub generation: u64,
    pub manifest_id: OperationId,
    pub manifest_blake3: String,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum S3CheckpointRelation {
    FirstObservation,
    Unchanged,
    Advanced,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum S3CheckpointError {
    InvalidScope, InvalidChain, InvalidStoredAnchor, ScopeMismatch,
    Rollback, DivergentHistory,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct UntrustedCheckpointProof {
    pub relation: S3CheckpointRelation,
    pub candidate: S3ObservedAnchorV1,
    /// No remote identity proof, no local document-binding, no import permit.
    pub remote_owner_verified: bool,
    pub import_authorized: bool,
}

fn lowercase_blake3(s: &str) -> bool {
    s.len() == 64 && s.bytes().all(|b|
        b.is_ascii_digit() || (b'a'..=b'f').contains(&b)
    )
}

impl S3ObservedAnchorV1 {
    pub fn encode(&self) -> Vec<u8> {
        serde_json::to_vec(self).expect("static checkpoint schema serializes")
    }

    pub fn parse(bytes: &[u8]) -> Result<Self, S3CheckpointError> {
        if bytes.is_empty() || bytes.len() > MAX_ANCHOR_BYTES {
            return Err(S3CheckpointError::InvalidStoredAnchor);
        }
        let anchor: Self = serde_json::from_slice(bytes)
            .map_err(|_| S3CheckpointError::InvalidStoredAnchor)?;
        if anchor.schema != SCHEMA || anchor.generation == 0
            || !lowercase_blake3(&anchor.scope_fingerprint)
            || !lowercase_blake3(&anchor.manifest_blake3)
            || anchor.encode() != bytes
        {
            return Err(S3CheckpointError::InvalidStoredAnchor);
        }
        Ok(anchor)
    }
}

/// Caller must supply a source scope derived from provider+endpoint+region+
/// bucket+prefix+vault+genesis; never credential values. All of the supplied
/// chain must pass the Core canonical genesis verifier before comparing pins.
pub fn compare_untrusted_s3_head_checkpoint(
    prefix: &str, vault_id: &str, claimed_genesis: &str,
    scope_fingerprint: &str, head: &[u8],
    manifests_newest_first: &[&[u8]], stored: Option<&[u8]>,
) -> Result<UntrustedCheckpointProof, S3CheckpointError> {
    if !lowercase_blake3(scope_fingerprint) {
        return Err(S3CheckpointError::InvalidScope);
    }
    check_untrusted_genesis_chain(
        prefix, vault_id, claimed_genesis, head, manifests_newest_first,
    ).map_err(|_| S3CheckpointError::InvalidChain)?;
    let vault = VaultId::from_str(vault_id)
        .map_err(|_| S3CheckpointError::InvalidScope)?;
    let ns = S3Keyspace::new(prefix, vault)
        .map_err(|_| S3CheckpointError::InvalidScope)?;
    let current = S3HeadPointerV1::decode(head, &ns)
        .map_err(|_| S3CheckpointError::InvalidChain)?;
    let candidate = S3ObservedAnchorV1 {
        schema: SCHEMA, scope_fingerprint: scope_fingerprint.to_owned(),
        generation: current.generation(),
        manifest_id: current.manifest_id(),
        manifest_blake3: current.manifest_blake3().to_owned(),
    };
    let relation = match stored {
        None => S3CheckpointRelation::FirstObservation,
        Some(bytes) => {
            let old = S3ObservedAnchorV1::parse(bytes)?;
            if old.scope_fingerprint != scope_fingerprint {
                return Err(S3CheckpointError::ScopeMismatch);
            }
            if old.generation > candidate.generation {
                return Err(S3CheckpointError::Rollback);
            }
            if old.generation == candidate.generation {
                if old.manifest_id != candidate.manifest_id
                    || old.manifest_blake3 != candidate.manifest_blake3
                {
                    return Err(S3CheckpointError::DivergentHistory);
                }
                S3CheckpointRelation::Unchanged
            } else {
                // The FULL already-verified chain must contain the previous
                // immutable identity with its pinned generation and digest.
                // Merely increasing a mutable JSON generation is insufficient.
                let found = manifests_newest_first.iter().any(|bytes| {
                    serde_json::from_slice::<S3ManifestV1>(bytes).ok()
                        .is_some_and(|m| m.generation == old.generation
                            && m.manifest_id == old.manifest_id
                            && m.digest(&ns).ok().as_deref()
                                == Some(old.manifest_blake3.as_str()))
                });
                if !found {
                    return Err(S3CheckpointError::DivergentHistory);
                }
                S3CheckpointRelation::Advanced
            }
        }
    };
    Ok(UntrustedCheckpointProof {
        relation, candidate, remote_owner_verified: false,
        import_authorized: false,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Sample {
        ns: S3Keyspace,
        genesis_pin: String,
        one: Vec<u8>,
        two: Vec<u8>,
        head_one: Vec<u8>,
        head_two: Vec<u8>,
    }

    fn sample() -> Sample {
        let ns = S3Keyspace::new(
            "akula-sync/pair", VaultId::new(),
        ).unwrap();
        let a = S3ManifestV1::new(&ns, OperationId::new(), 1, None, vec![]).unwrap();
        let a_hash = a.digest(&ns).unwrap();
        let first = S3HeadPointerV1::new(&ns, 1, a.manifest_id, &a_hash, None).unwrap();
        let b = S3ManifestV1::new(&ns, OperationId::new(), 2,
            Some((a.manifest_id, a_hash)), vec![]).unwrap();
        let second = S3HeadPointerV1::new(&ns, 2, b.manifest_id,
            &b.digest(&ns).unwrap(), Some(a.manifest_id)).unwrap();
        let pin = blake3::hash(&first.encode(&ns).unwrap()).to_hex().to_string();
        Sample {
            ns: ns.clone(), genesis_pin: pin,
            one: a.encode(&ns).unwrap(), two: b.encode(&ns).unwrap(),
            head_one: first.encode(&ns).unwrap(), head_two: second.encode(&ns).unwrap(),
        }
    }

    fn compare(s: &Sample, head: &[u8], chain: &[&[u8]], stored: Option<&[u8]>)
        -> Result<UntrustedCheckpointProof, S3CheckpointError>
    {
        compare_untrusted_s3_head_checkpoint(
            "akula-sync/pair", &s.ns.vault_id().to_string(),
            &s.genesis_pin, &"a".repeat(64), head, chain, stored,
        )
    }

    #[test]
    fn first_observation_then_unchanged_and_child_generation() {
        let s = sample();
        let genesis = compare(&s, &s.head_one, &[&s.one], None).unwrap();
        assert_eq!(genesis.relation, S3CheckpointRelation::FirstObservation);
        let bytes = genesis.candidate.encode();
        assert_eq!(S3ObservedAnchorV1::parse(&bytes).unwrap(), genesis.candidate);
        assert_eq!(compare(&s, &s.head_one, &[&s.one], Some(&bytes))
            .unwrap().relation, S3CheckpointRelation::Unchanged);
        let next = compare(&s, &s.head_two, &[&s.two, &s.one], Some(&bytes)).unwrap();
        assert_eq!(next.relation, S3CheckpointRelation::Advanced);
        assert!(!next.remote_owner_verified && !next.import_authorized);
    }

    #[test]
    fn rollback_same_generation_fork_and_replaced_scope_fail_closed() {
        let s = sample();
        let second = compare(&s, &s.head_two, &[&s.two, &s.one], None)
            .unwrap().candidate.encode();
        assert_eq!(compare(&s, &s.head_one, &[&s.one], Some(&second)),
            Err(S3CheckpointError::Rollback));
        let old = compare(&s, &s.head_two, &[&s.two, &s.one], None)
            .unwrap().candidate;
        let mut altered = old.clone();
        altered.manifest_blake3 = "b".repeat(64);
        assert_eq!(compare(&s, &s.head_two, &[&s.two, &s.one], Some(&altered.encode())),
            Err(S3CheckpointError::DivergentHistory));
        altered = old;
        altered.scope_fingerprint = "f".repeat(64);
        assert_eq!(compare(&s, &s.head_two, &[&s.two, &s.one], Some(&altered.encode())),
            Err(S3CheckpointError::ScopeMismatch));
    }

    #[test]
    fn forged_progression_without_pinned_ancestor_fails() {
        let s = sample();
        let old = compare(&s, &s.head_one, &[&s.one], None).unwrap().candidate;
        let mut fake = old.clone();
        fake.manifest_id = OperationId::new();
        assert_eq!(compare(&s, &s.head_two, &[&s.two, &s.one], Some(&fake.encode())),
            Err(S3CheckpointError::DivergentHistory));
        let mut noncanonical = old.encode();
        noncanonical.push(b' ');
        assert_eq!(compare(&s, &s.head_one, &[&s.one], Some(&noncanonical)),
            Err(S3CheckpointError::InvalidStoredAnchor));
    }

    #[test]
    fn corrupt_remote_chain_never_evaluates_a_checkpoint() {
        let s = sample();
        let old = compare(&s, &s.head_one, &[&s.one], None).unwrap().candidate.encode();
        assert_eq!(compare(&s, &s.head_two, &[&s.one, &s.two], Some(&old)),
            Err(S3CheckpointError::InvalidChain));
        assert!(S3ObservedAnchorV1::parse(b"{}").is_err());
    }
}
