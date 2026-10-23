//! S4a: bounded immutable S3 revision JSON proof, strictly read-only.
//! A valid root/index is not proof of remote ownership or permission to import.
//! The Android adapter fetches exact typed IDs and rereads the root afterwards.

use std::{collections::{BTreeMap, BTreeSet}, str::FromStr};
use aquilum_core::sync::{
    RevisionDag, RevisionOperation, RevisionRecord, S3HeadPointerV1,
    S3Keyspace, S3ManifestShardV1, S3ManifestV1, VaultId,
};
use crate::s3_index::inspect_untrusted_sharded_index;
pub use aquilum_core::sync::S3RevisionRefV1;

pub const MAX_REVISION_PREVIEW: usize = 64;
pub const MAX_REVISION_JSON_BYTES: usize = 64 * 1024;

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum S3RevisionProofError {
    IndexInvalid, InvalidScope, TooManyRevisions, MissingRevision,
    Oversized, DigestMismatch, InvalidRevision, InvalidDag,
    DuplicateRevision, InconsistentBlobSize,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct UntrustedS3RevisionPreview {
    pub revisions_checked: usize,
    pub content_revisions: usize,
    pub rename_revisions: usize,
    pub tombstones: usize,
    pub remote_owner_verified: bool,
}

/// A list of exact remote revision identities, only from a fully verified
/// V1 sharded index. The JNI session must whitelist these before signing GET.
pub fn collect_untrusted_revision_refs(
    prefix: &str, cloud_vault_id: &str, head: &[u8],
    latest: &[u8], shards: &[&[u8]],
) -> Result<Vec<S3RevisionRefV1>, S3RevisionProofError> {
    let index = inspect_untrusted_sharded_index(
        prefix, cloud_vault_id, head, latest, shards,
    ).map_err(|_| S3RevisionProofError::IndexInvalid)?;
    if index.revisions > MAX_REVISION_PREVIEW {
        return Err(S3RevisionProofError::TooManyRevisions);
    }
    let vault_id = VaultId::from_str(cloud_vault_id)
        .map_err(|_| S3RevisionProofError::InvalidScope)?;
    let ns = S3Keyspace::new(prefix, vault_id)
        .map_err(|_| S3RevisionProofError::InvalidScope)?;
    let ptr = S3HeadPointerV1::decode(head, &ns)
        .map_err(|_| S3RevisionProofError::IndexInvalid)?;
    let manifest = S3ManifestV1::decode(&ns, ptr.manifest_id(), latest)
        .map_err(|_| S3RevisionProofError::IndexInvalid)?;
    let mut refs = Vec::with_capacity(index.revisions);
    for (reference, raw) in manifest.shards.iter().zip(shards) {
        let shard = S3ManifestShardV1::decode(&ns, reference.shard_id, raw)
            .map_err(|_| S3RevisionProofError::IndexInvalid)?;
        refs.extend(shard.revisions);
    }
    if refs.len() != index.revisions {
        return Err(S3RevisionProofError::IndexInvalid);
    }
    Ok(refs)
}

/// Requires every revision JSON referenced by the same verified index, in
/// exact index order. Checks raw BLAKE3, typed record identity and Core DAG.
/// Does not fetch blobs, reveal paths to Kotlin, persist data, or grant trust.
pub fn inspect_untrusted_revision_metadata(
    prefix: &str, cloud_vault_id: &str, head: &[u8],
    latest: &[u8], shards: &[&[u8]], revision_json: &[&[u8]],
) -> Result<UntrustedS3RevisionPreview, S3RevisionProofError> {
    let refs = collect_untrusted_revision_refs(
        prefix, cloud_vault_id, head, latest, shards,
    )?;
    inspect_untrusted_revision_records(&refs, revision_json)
}

/// The caller must supply references from a successful exact-root index
/// proof in the same expiring native session; no trust or file authority
/// is derived from this offline validation.
pub fn inspect_untrusted_revision_records(
    refs: &[S3RevisionRefV1], revision_json: &[&[u8]],
) -> Result<UntrustedS3RevisionPreview, S3RevisionProofError> {
    if refs.len() > MAX_REVISION_PREVIEW {
        return Err(S3RevisionProofError::TooManyRevisions);
    }
    if refs.len() != revision_json.len() {
        return Err(S3RevisionProofError::MissingRevision);
    }
    let mut total_bytes = 0usize;
    let mut seen = BTreeSet::new();
    let mut blob_lengths = BTreeMap::new();
    let mut dag = RevisionDag::new();
    let mut content = 0usize;
    let mut renames = 0usize;
    let mut tombstones = 0usize;
    for (reference, raw) in refs.iter().zip(revision_json) {
        total_bytes = total_bytes.checked_add(raw.len())
            .ok_or(S3RevisionProofError::Oversized)?;
        if raw.is_empty() || raw.len() > MAX_REVISION_JSON_BYTES
            || total_bytes > MAX_REVISION_PREVIEW * MAX_REVISION_JSON_BYTES
        {
            return Err(S3RevisionProofError::Oversized);
        }
        if blake3::hash(raw).to_hex().as_str() != reference.revision_blake3 {
            return Err(S3RevisionProofError::DigestMismatch);
        }
        let record: RevisionRecord = serde_json::from_slice(raw)
            .map_err(|_| S3RevisionProofError::InvalidRevision)?;
        if record.id != reference.revision_id
            || record.document_id != reference.document_id
        {
            return Err(S3RevisionProofError::InvalidRevision);
        }
        if !seen.insert(record.id) {
            return Err(S3RevisionProofError::DuplicateRevision);
        }
        match &record.operation {
            RevisionOperation::Content { blob_hash, .. }
            | RevisionOperation::Rename { blob_hash, .. } => {
                let size = reference.blob_bytes
                    .ok_or(S3RevisionProofError::InvalidRevision)?;
                if blob_lengths.insert(blob_hash.clone(), size)
                    .is_some_and(|previous| previous != size)
                {
                    return Err(S3RevisionProofError::InconsistentBlobSize);
                }
                if matches!(&record.operation, RevisionOperation::Content { .. }) {
                    content += 1;
                } else {
                    renames += 1;
                }
            }
            RevisionOperation::Tombstone { .. } => {
                if reference.blob_bytes.is_some() {
                    return Err(S3RevisionProofError::InvalidRevision);
                }
                tombstones += 1;
            }
        }
        dag.ingest(record).map_err(|_| S3RevisionProofError::InvalidDag)?;
    }
    if dag.pending_len() != 0 || dag.active_len() != refs.len() {
        return Err(S3RevisionProofError::InvalidDag);
    }
    Ok(UntrustedS3RevisionPreview {
        revisions_checked: refs.len(), content_revisions: content,
        rename_revisions: renames, tombstones,
        remote_owner_verified: false,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use aquilum_core::sync::{
        DocumentId, OperationId, RevisionId, SyncRelativePath,
    };

    fn record() -> RevisionRecord {
        RevisionRecord {
            id: RevisionId::new(), document_id: DocumentId::new(),
            parents: vec![],
            operation: RevisionOperation::Content {
                path: SyncRelativePath::parse("example.md").unwrap(),
                blob_hash: blake3::hash(b"example").to_hex().to_string(),
            },
        }
    }

    fn fixture(record: RevisionRecord)
        -> (String, Vec<u8>, Vec<u8>, Vec<u8>, Vec<u8>)
    {
        let ns = S3Keyspace::new("akula-sync/pair", VaultId::new()).unwrap();
        let raw = serde_json::to_vec(&record).unwrap();
        let shard = S3ManifestShardV1::new(&ns, OperationId::new(), vec![
            S3RevisionRefV1 {
                revision_id: record.id, document_id: record.document_id,
                revision_blake3: blake3::hash(&raw).to_hex().to_string(),
                blob_bytes: Some(7),
            },
        ]).unwrap();
        let manifest = S3ManifestV1::new(&ns, OperationId::new(), 1, None,
            vec![shard.reference(&ns).unwrap()]).unwrap();
        let ptr = S3HeadPointerV1::new(&ns, 1, manifest.manifest_id,
            &manifest.digest(&ns).unwrap(), None).unwrap();
        (ns.vault_id().to_string(), ptr.encode(&ns).unwrap(),
         manifest.encode(&ns).unwrap(), shard.encode(&ns).unwrap(), raw)
    }

    #[test]
    fn exact_desktop_core_revision_record_is_read_only_valid() {
        let (vault, head, manifest, shard, raw) = fixture(record());
        let refs = collect_untrusted_revision_refs(
            "akula-sync/pair", &vault, &head, &manifest, &[&shard],
        ).unwrap();
        assert_eq!(refs.len(), 1);
        let proof = inspect_untrusted_revision_metadata(
            "akula-sync/pair", &vault, &head, &manifest, &[&shard], &[&raw],
        ).unwrap();
        assert_eq!((proof.revisions_checked, proof.content_revisions), (1, 1));
        assert!(!proof.remote_owner_verified);
    }

    #[test]
    fn missing_modified_or_foreign_revision_is_rejected() {
        let (vault, head, manifest, shard, raw) = fixture(record());
        assert_eq!(inspect_untrusted_revision_metadata(
            "akula-sync/pair", &vault, &head, &manifest, &[&shard], &[],
        ), Err(S3RevisionProofError::MissingRevision));
        let mut altered = raw.clone();
        altered.push(b' ');
        assert_eq!(inspect_untrusted_revision_metadata(
            "akula-sync/pair", &vault, &head, &manifest, &[&shard], &[&altered],
        ), Err(S3RevisionProofError::DigestMismatch));
        assert!(inspect_untrusted_revision_metadata(
            "akula-sync/pair", &VaultId::new().to_string(),
            &head, &manifest, &[&shard], &[&raw],
        ).is_err());
    }

    #[test]
    fn dangling_revision_parent_fails_closed() {
        let mut next = record();
        next.parents.push(RevisionId::new());
        let (vault, head, manifest, shard, raw) = fixture(next);
        assert_eq!(inspect_untrusted_revision_metadata(
            "akula-sync/pair", &vault, &head, &manifest, &[&shard], &[&raw],
        ), Err(S3RevisionProofError::InvalidDag));
    }

    #[test]
    fn empty_index_is_valid_without_fabricated_revisions() {
        let ns = S3Keyspace::new("akula-sync/pair", VaultId::new()).unwrap();
        let m = S3ManifestV1::new(&ns, OperationId::new(), 1, None, vec![]).unwrap();
        let h = S3HeadPointerV1::new(&ns, 1, m.manifest_id,
            &m.digest(&ns).unwrap(), None).unwrap();
        let proof = inspect_untrusted_revision_metadata(
            "akula-sync/pair", &ns.vault_id().to_string(),
            &h.encode(&ns).unwrap(), &m.encode(&ns).unwrap(), &[], &[],
        ).unwrap();
        assert_eq!(proof.revisions_checked, 0);
    }
}
