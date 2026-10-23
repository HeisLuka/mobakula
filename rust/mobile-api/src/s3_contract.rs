//! S0: STRUCTURAL, UNTRUSTED S3 format admission using actual Akula Core types.
//!
//! This is not a network client, a genesis trust check, a local file writer,
//! or an authorization to advance a Sync journal. Never report "Synced" here.

use std::str::FromStr;

use aquilum_core::sync::{
    S3ContractError, S3HeadPointerV1, S3Keyspace, S3ManifestError,
    S3ManifestV1, VaultId,
};

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum S3ShapeError {
    InvalidVaultId,
    Namespace(S3ContractError),
    Head(S3ContractError),
    Manifest(S3ManifestError),
    NonCanonicalHead,
    NonCanonicalManifest,
    HeadManifestMismatch,
}

/// The name is deliberate: a self-consistent S3 object pair can still be forged.
/// Only a separate trusted genesis/publisher proof can admit remote data.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct UntrustedS3Shape {
    pub vault_id: String,
    pub current_head_key: String,
    pub immutable_manifest_key: String,
    pub generation: u64,
    pub total_revisions: u32,
}

/// Inspects raw bytes; performs NO S3 requests and NO local File Gate writes.
/// The desktop's S3HeadPointerV1 and S3ManifestV1 supply the only parsers.
///
/// This validates shape and content-address linkage, NOT the publisher,
/// genesis, ancestor chain, CAS correctness, or authorization to receive.
/// Network code must use the existing verified Core pinning APIs later.
pub fn inspect_untrusted_s3_shape(
    prefix: &str,
    cloud_vault_id: &str,
    head_bytes: &[u8],
    manifest_bytes: &[u8],
) -> Result<UntrustedS3Shape, S3ShapeError> {
    let vault_id = VaultId::from_str(cloud_vault_id)
        .map_err(|_| S3ShapeError::InvalidVaultId)?;
    let ns = S3Keyspace::new(prefix, vault_id)
        .map_err(S3ShapeError::Namespace)?;
    let head = S3HeadPointerV1::decode(head_bytes, &ns)
        .map_err(S3ShapeError::Head)?;
    if head.encode(&ns).map_err(S3ShapeError::Head)?.as_slice() != head_bytes {
        return Err(S3ShapeError::NonCanonicalHead);
    }
    let manifest = S3ManifestV1::decode(&ns, head.manifest_id(), manifest_bytes)
        .map_err(S3ShapeError::Manifest)?;
    if manifest.encode(&ns).map_err(S3ShapeError::Manifest)?.as_slice() != manifest_bytes {
        return Err(S3ShapeError::NonCanonicalManifest);
    }
    if manifest.generation != head.generation()
        || manifest.previous_manifest_id != head.previous_manifest_id()
        || manifest.digest(&ns).map_err(S3ShapeError::Manifest)?
            != head.manifest_blake3()
    {
        return Err(S3ShapeError::HeadManifestMismatch);
    }
    let immutable_manifest_key = head.immutable_manifest_key(&ns)
        .map_err(S3ShapeError::Head)?;
    Ok(UntrustedS3Shape {
        vault_id: vault_id.to_string(),
        current_head_key: ns.current_head_key(),
        immutable_manifest_key,
        generation: head.generation(),
        total_revisions: manifest.total_revisions,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::str::FromStr;
    use aquilum_core::sync::{OperationId, S3ManifestV1};

    const VAULT: &str = "11111111-2222-4333-8444-555555555555";
    const MANIFEST: &str = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
    const PREFIX: &str = "akula-sync/pair";

    fn genesis() -> (S3Keyspace, Vec<u8>, Vec<u8>) {
        let ns = S3Keyspace::new(PREFIX, VaultId::from_str(VAULT).unwrap()).unwrap();
        let id = OperationId::from_str(MANIFEST).unwrap();
        let manifest = S3ManifestV1::new(&ns, id, 1, None, vec![]).unwrap();
        let digest = manifest.digest(&ns).unwrap();
        let head = S3HeadPointerV1::new(&ns, 1, id, &digest, None).unwrap();
        (ns, head.encode(&ns).unwrap(), manifest.encode(&ns).unwrap())
    }

    #[test]
    fn desktop_v1_genesis_has_the_exact_wire_keys_and_layout() {
        let (ns, head, manifest) = genesis();
        let expected_manifest = format!(
            r#"{{"schema":1,"vault_id":"{VAULT}","manifest_id":"{MANIFEST}","generation":1,"previous_manifest_id":null,"previous_manifest_blake3":null,"total_revisions":0,"shards":[]}}"#
        );
        assert_eq!(manifest, expected_manifest.as_bytes());
        let hash = S3ManifestV1::decode(
            &ns, OperationId::from_str(MANIFEST).unwrap(), &manifest
        ).unwrap().digest(&ns).unwrap();
        let expected_head = format!(
            r#"{{"schema":1,"vault_id":"{VAULT}","generation":1,"manifest_id":"{MANIFEST}","manifest_blake3":"{hash}","previous_manifest_id":null}}"#
        );
        assert_eq!(head, expected_head.as_bytes());
        let preview = inspect_untrusted_s3_shape(PREFIX, VAULT, &head, &manifest).unwrap();
        assert_eq!(preview.generation, 1);
        assert_eq!(preview.total_revisions, 0);
        assert_eq!(preview.current_head_key,
            format!("{PREFIX}/v1/vaults/{VAULT}/refs/current.json"));
        assert_eq!(preview.immutable_manifest_key,
            format!("{PREFIX}/v1/vaults/{VAULT}/manifests/{MANIFEST}.json"));
    }

    #[test]
    fn wrong_namespace_vault_and_unknown_authority_fail_closed() {
        let (_, head, manifest) = genesis();
        for prefix in ["", "akula-sync/../pair", "akula-sync/Pair"] {
            assert!(matches!(inspect_untrusted_s3_shape(prefix, VAULT, &head, &manifest),
                Err(S3ShapeError::Namespace(_))));
        }
        assert_eq!(inspect_untrusted_s3_shape(PREFIX, "bad", &head, &manifest),
            Err(S3ShapeError::InvalidVaultId));
        let foreign = "22222222-2222-4333-8444-555555555555";
        assert!(matches!(inspect_untrusted_s3_shape(PREFIX, foreign, &head, &manifest),
            Err(S3ShapeError::Head(S3ContractError::ForeignVault))));
        let mut value: serde_json::Value = serde_json::from_slice(&head).unwrap();
        value["authorize_write"] = true.into();
        let extra = serde_json::to_vec(&value).unwrap();
        assert!(inspect_untrusted_s3_shape(PREFIX, VAULT, &extra, &manifest).is_err());
    }

    #[test]
    fn mismatched_generation_digest_and_manifest_identity_fail_closed() {
        let (ns, head, manifest) = genesis();
        let id = OperationId::from_str(MANIFEST).unwrap();
        let zero_hash = "0".repeat(64);
        let modified = S3HeadPointerV1::new(&ns, 1, id, &zero_hash, None)
            .unwrap().encode(&ns).unwrap();
        assert_eq!(inspect_untrusted_s3_shape(PREFIX, VAULT, &modified, &manifest),
            Err(S3ShapeError::HeadManifestMismatch));
        let other = OperationId::new();
        let successor = S3HeadPointerV1::new(&ns, 2, id, &zero_hash, Some(other))
            .unwrap().encode(&ns).unwrap();
        assert_eq!(inspect_untrusted_s3_shape(PREFIX, VAULT, &successor, &manifest),
            Err(S3ShapeError::HeadManifestMismatch));
        let missing = S3ManifestV1::new(&ns, OperationId::new(), 1, None, vec![])
            .unwrap().encode(&ns).unwrap();
        assert!(matches!(inspect_untrusted_s3_shape(PREFIX, VAULT, &head, &missing),
            Err(S3ShapeError::Manifest(_))));
    }

    #[test]
    fn noncanonical_truncated_and_oversized_data_are_rejected() {
        let (_, head, manifest) = genesis();
        assert_eq!(inspect_untrusted_s3_shape(
            PREFIX, VAULT, &[head.as_slice(), b" "].concat(), &manifest),
            Err(S3ShapeError::NonCanonicalHead));
        assert_eq!(inspect_untrusted_s3_shape(
            PREFIX, VAULT, &head, &[manifest.as_slice(), b" "].concat()),
            Err(S3ShapeError::NonCanonicalManifest));
        for broken in [&head[..3], &head[..head.len() - 1], &[0u8; 0][..]] {
            assert!(inspect_untrusted_s3_shape(PREFIX, VAULT, broken, &manifest).is_err());
        }
        assert!(inspect_untrusted_s3_shape(
            PREFIX, VAULT, &head, &vec![b'X'; 512 * 1024 + 1]).is_err());
        assert!(inspect_untrusted_s3_shape(
            PREFIX, VAULT, &vec![b'X'; 4097], &manifest).is_err());
    }
}
