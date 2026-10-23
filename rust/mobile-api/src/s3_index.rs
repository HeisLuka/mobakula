//! Read-only sharded S3 index proof using shared Akula Core V1 types.
//! Does not authenticate a provider or grant File Gate authority.

use std::{collections::BTreeSet, str::FromStr};
use aquilum_core::sync::{
    S3HeadPointerV1, S3Keyspace, S3ManifestV1, S3ManifestShardV1, VaultId,
};

pub const MAX_PREVIEW_SHARDS: usize = 16;
pub const MAX_SHARD_BYTES: usize = 128 * 1024;

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum S3IndexError {
    InvalidScope, InvalidHead, InvalidManifest, NonCanonical,
    ManifestDigestMismatch, InvalidShard, ShardDigestMismatch,
    MissingShard, TooManyShards, Oversized, RevisionCountMismatch,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct UntrustedS3IndexPreview {
    pub revisions: usize,
    pub distinct_document_ids: usize,
    pub shards_checked: usize,
    pub remote_owner_verified: bool,
}

/// Input shards are ordered like latest_manifest.shards and independently
/// fetched through bounded, namespace-scoped, stable HTTP reads. The caller
/// must recheck the exact root and ETag after the final shard fetch.
pub fn inspect_untrusted_sharded_index(
    prefix: &str, cloud_vault_id: &str, current_head: &[u8],
    latest_manifest: &[u8], shard_bytes: &[&[u8]],
) -> Result<UntrustedS3IndexPreview, S3IndexError> {
    let vault_id = VaultId::from_str(cloud_vault_id)
        .map_err(|_| S3IndexError::InvalidScope)?;
    let ns = S3Keyspace::new(prefix, vault_id)
        .map_err(|_| S3IndexError::InvalidScope)?;
    if current_head.len() > 4096 || latest_manifest.len() > 512 * 1024 {
        return Err(S3IndexError::Oversized);
    }
    let ptr = S3HeadPointerV1::decode(current_head, &ns)
        .map_err(|_| S3IndexError::InvalidHead)?;
    if ptr.encode(&ns).map_err(|_| S3IndexError::InvalidHead)? != current_head {
        return Err(S3IndexError::NonCanonical);
    }
    let root = S3ManifestV1::decode(&ns, ptr.manifest_id(), latest_manifest)
        .map_err(|_| S3IndexError::InvalidManifest)?;
    if root.encode(&ns).map_err(|_| S3IndexError::InvalidManifest)?
        != latest_manifest
    {
        return Err(S3IndexError::NonCanonical);
    }
    if root.digest(&ns).map_err(|_| S3IndexError::InvalidManifest)?
        != ptr.manifest_blake3()
    {
        return Err(S3IndexError::ManifestDigestMismatch);
    }
    if root.generation != ptr.generation()
        || root.previous_manifest_id != ptr.previous_manifest_id()
    {
        return Err(S3IndexError::InvalidManifest);
    }
    if root.shards.len() > MAX_PREVIEW_SHARDS {
        return Err(S3IndexError::TooManyShards);
    }
    if shard_bytes.len() != root.shards.len() {
        return Err(S3IndexError::MissingShard);
    }
    let mut count = 0usize;
    let mut docs = BTreeSet::new();
    let mut total = 0usize;
    for (reference, bytes) in root.shards.iter().zip(shard_bytes) {
        total = total.checked_add(bytes.len())
            .ok_or(S3IndexError::Oversized)?;
        if bytes.len() > MAX_SHARD_BYTES
            || total > MAX_PREVIEW_SHARDS * MAX_SHARD_BYTES
        {
            return Err(S3IndexError::Oversized);
        }
        let shard = S3ManifestShardV1::decode(&ns, reference.shard_id, bytes)
            .map_err(|_| S3IndexError::InvalidShard)?;
        if shard.encode(&ns).map_err(|_| S3IndexError::InvalidShard)? != *bytes {
            return Err(S3IndexError::NonCanonical);
        }
        if shard.reference(&ns).map_err(|_| S3IndexError::InvalidShard)?
            != *reference
        {
            return Err(S3IndexError::ShardDigestMismatch);
        }
        count += shard.revisions.len();
        for rev in &shard.revisions { docs.insert(rev.document_id); }
    }
    if count != root.total_revisions as usize {
        return Err(S3IndexError::RevisionCountMismatch);
    }
    Ok(UntrustedS3IndexPreview {
        revisions: count, distinct_document_ids: docs.len(),
        shards_checked: shard_bytes.len(), remote_owner_verified: false,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use aquilum_core::sync::{DocumentId, OperationId, RevisionId, S3RevisionRefV1};

    fn sample() -> (String, Vec<u8>, Vec<u8>, Vec<u8>) {
        let id = VaultId::new();
        let ns = S3Keyspace::new("akula-sync/pair", id).unwrap();
        let shard = S3ManifestShardV1::new(&ns, OperationId::new(), vec![
            S3RevisionRefV1 {
                revision_id: RevisionId::new(), document_id: DocumentId::new(),
                revision_blake3: "a".repeat(64), blob_bytes: Some(5),
            },
        ]).unwrap();
        let root = S3ManifestV1::new(&ns, OperationId::new(), 1, None,
            vec![shard.reference(&ns).unwrap()]).unwrap();
        let head = S3HeadPointerV1::new(&ns, 1, root.manifest_id,
            &root.digest(&ns).unwrap(), None).unwrap();
        (id.to_string(), head.encode(&ns).unwrap(),
         root.encode(&ns).unwrap(), shard.encode(&ns).unwrap())
    }

    #[test]
    fn shared_core_nonempty_index_is_validated() {
        let (v, h, m, s) = sample();
        let x = inspect_untrusted_sharded_index("akula-sync/pair", &v, &h, &m,
            &[&s]).unwrap();
        assert_eq!((x.revisions, x.distinct_document_ids, x.shards_checked),
            (1, 1, 1));
        assert!(!x.remote_owner_verified);
    }

    #[test]
    fn missing_extra_corrupt_or_noncanonical_shards_are_rejected() {
        let (v, h, m, s) = sample();
        assert_eq!(inspect_untrusted_sharded_index("akula-sync/pair", &v,
            &h, &m, &[]), Err(S3IndexError::MissingShard));
        assert_eq!(inspect_untrusted_sharded_index("akula-sync/pair", &v,
            &h, &m, &[&s, &s]), Err(S3IndexError::MissingShard));
        let mut bad = s.clone();
        *bad.last_mut().unwrap() ^= 1;
        assert!(inspect_untrusted_sharded_index("akula-sync/pair", &v,
            &h, &m, &[&bad]).is_err());
        let mut spaced = s;
        spaced.push(b' ');
        assert_eq!(inspect_untrusted_sharded_index("akula-sync/pair", &v,
            &h, &m, &[&spaced]), Err(S3IndexError::NonCanonical));
    }

    #[test]
    fn foreign_namespace_oversize_and_extra_manifest_data_are_rejected() {
        let (v, h, m, s) = sample();
        assert!(inspect_untrusted_sharded_index("akula-sync/foreign", &v,
            &h, &m, &[&s]).is_err());
        assert_eq!(inspect_untrusted_sharded_index("akula-sync/pair", &v,
            &h, &[m.as_slice(), b" "].concat(), &[&s]),
            Err(S3IndexError::NonCanonical));
        assert_eq!(inspect_untrusted_sharded_index("akula-sync/pair", &v,
            &vec![0; 4097], &m, &[&s]), Err(S3IndexError::Oversized));
        assert_eq!(inspect_untrusted_sharded_index("akula-sync/pair", &v,
            &h, &m, &[&vec![0; MAX_SHARD_BYTES + 1]]),
            Err(S3IndexError::Oversized));
    }

    #[test]
    fn empty_genesis_index_is_not_a_missing_shard() {
        let ns = S3Keyspace::new("akula-sync/pair", VaultId::new()).unwrap();
        let root = S3ManifestV1::new(&ns, OperationId::new(), 1, None, vec![])
            .unwrap();
        let head = S3HeadPointerV1::new(&ns, 1, root.manifest_id,
            &root.digest(&ns).unwrap(), None).unwrap();
        let x = inspect_untrusted_sharded_index("akula-sync/pair",
            &ns.vault_id().to_string(), &head.encode(&ns).unwrap(),
            &root.encode(&ns).unwrap(), &[]).unwrap();
        assert_eq!(x.revisions, 0);
        assert_eq!(x.distinct_document_ids, 0);
    }
}
