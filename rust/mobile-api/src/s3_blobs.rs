//! S4c: bounded, read-only proof of immutable S3 content blobs.
//! No local file creation, body rendering, ownership claim or sync receipt.

use std::collections::{BTreeMap, BTreeSet};
use aquilum_core::sync::{RevisionOperation, RevisionRecord};
use crate::s3_revisions::{inspect_untrusted_revision_records, S3RevisionRefV1};

pub const MAX_BLOB_COUNT: usize = 16;
pub const MAX_BLOB_BYTES: usize = 1024 * 1024;
pub const MAX_BLOB_TOTAL: usize = 8 * 1024 * 1024;

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum S3BlobProofError {
    InvalidRevisionProof, InvalidRevision, InconsistentLength,
    PreviewLimit, MissingBlob, DuplicateBlob, WrongLength, WrongDigest,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct S3BlobExpectation {
    pub blake3: String,
    pub bytes: usize,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct UntrustedS3BlobPreview {
    pub blobs_checked: usize,
    pub bytes_checked: usize,
    pub remote_owner_verified: bool,
}

/// Only a complete, valid revision DAG from the SAME verified S3 index
/// can authorize blob hashes. Duplicate content is fetched once.
/// PreviewLimit rejects the entire selection; it never yields a partial list.
pub fn collect_bounded_blob_expectations(
    references: &[S3RevisionRefV1], revision_json: &[&[u8]],
) -> Result<Vec<S3BlobExpectation>, S3BlobProofError> {
    inspect_untrusted_revision_records(references, revision_json)
        .map_err(|_| S3BlobProofError::InvalidRevisionProof)?;
    let mut by_hash = BTreeMap::<String, usize>::new();
    for (reference, raw) in references.iter().zip(revision_json) {
        let record: RevisionRecord = serde_json::from_slice(raw)
            .map_err(|_| S3BlobProofError::InvalidRevision)?;
        match record.operation {
            RevisionOperation::Content { blob_hash, .. }
            | RevisionOperation::Rename { blob_hash, .. } => {
                let bytes = reference.blob_bytes
                    .and_then(|len| usize::try_from(len).ok())
                    .ok_or(S3BlobProofError::PreviewLimit)?;
                if by_hash.insert(blob_hash.clone(), bytes)
                    .is_some_and(|previous| previous != bytes)
                {
                    return Err(S3BlobProofError::InconsistentLength);
                }
            }
            RevisionOperation::Tombstone { .. } => {}
        }
    }
    if by_hash.len() > MAX_BLOB_COUNT {
        return Err(S3BlobProofError::PreviewLimit);
    }
    let mut total = 0usize;
    let mut selection = Vec::with_capacity(by_hash.len());
    for (blake3, bytes) in by_hash {
        total = total.checked_add(bytes)
            .ok_or(S3BlobProofError::PreviewLimit)?;
        if bytes > MAX_BLOB_BYTES || total > MAX_BLOB_TOTAL {
            return Err(S3BlobProofError::PreviewLimit);
        }
        selection.push(S3BlobExpectation { blake3, bytes });
    }
    Ok(selection)
}

/// Raw bytes are kept in memory only by the caller. The verifier does not
/// interpret note text or create files. Empty blobs are valid if their exact
/// BLAKE3 matches the immutable revision's content hash.
pub fn verify_bounded_blob_bytes(
    expected: &[S3BlobExpectation], blobs: &[&[u8]],
) -> Result<UntrustedS3BlobPreview, S3BlobProofError> {
    if expected.len() > MAX_BLOB_COUNT {
        return Err(S3BlobProofError::PreviewLimit);
    }
    if expected.len() != blobs.len() {
        return Err(S3BlobProofError::MissingBlob);
    }
    let mut seen = BTreeSet::new();
    let mut total = 0usize;
    for (reference, bytes) in expected.iter().zip(blobs) {
        if !seen.insert(reference.blake3.as_str()) {
            return Err(S3BlobProofError::DuplicateBlob);
        }
        total = total.checked_add(bytes.len())
            .ok_or(S3BlobProofError::PreviewLimit)?;
        if bytes.len() > MAX_BLOB_BYTES || total > MAX_BLOB_TOTAL
            || reference.bytes > MAX_BLOB_BYTES
        {
            return Err(S3BlobProofError::PreviewLimit);
        }
        if bytes.len() != reference.bytes {
            return Err(S3BlobProofError::WrongLength);
        }
        if blake3::hash(bytes).to_hex().as_str() != reference.blake3 {
            return Err(S3BlobProofError::WrongDigest);
        }
    }
    Ok(UntrustedS3BlobPreview {
        blobs_checked: expected.len(),
        bytes_checked: total,
        remote_owner_verified: false,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use aquilum_core::sync::{DocumentId, RevisionId, SyncRelativePath};

    fn content(bytes: &[u8]) -> (S3RevisionRefV1, Vec<u8>) {
        let record = RevisionRecord {
            id: RevisionId::new(), document_id: DocumentId::new(), parents: vec![],
            operation: RevisionOperation::Content {
                path: SyncRelativePath::parse("note.md").unwrap(),
                blob_hash: blake3::hash(bytes).to_hex().to_string(),
            },
        };
        let raw = serde_json::to_vec(&record).unwrap();
        (S3RevisionRefV1 {
            revision_id: record.id, document_id: record.document_id,
            revision_blake3: blake3::hash(&raw).to_hex().to_string(),
            blob_bytes: Some(bytes.len() as u64),
        }, raw)
    }

    #[test]
    fn verified_content_and_empty_blob_do_not_create_files() {
        let (r1, raw1) = content(b"hello\n");
        let (r2, raw2) = content(b"");
        let refs = [r1, r2];
        let selection = collect_bounded_blob_expectations(
            &refs, &[&raw1, &raw2],
        ).unwrap();
        assert_eq!(selection.len(), 2);
        let bodies: Vec<&[u8]> = selection.iter().map(|item| {
            if item.bytes == 0 { &b""[..] } else { &b"hello\n"[..] }
        }).collect();
        let verified = verify_bounded_blob_bytes(&selection, &bodies).unwrap();
        assert_eq!(verified.blobs_checked, 2);
        assert_eq!(verified.bytes_checked, 6);
        assert!(!verified.remote_owner_verified);
    }

    #[test]
    fn corruption_wrong_size_and_missing_blob_fail_closed() {
        let (reference, raw) = content(b"hello");
        let selection = collect_bounded_blob_expectations(
            &[reference], &[&raw],
        ).unwrap();
        assert_eq!(verify_bounded_blob_bytes(&selection, &[]),
            Err(S3BlobProofError::MissingBlob));
        assert_eq!(verify_bounded_blob_bytes(&selection, &[b"short"]),
            Err(S3BlobProofError::WrongDigest));
        assert_eq!(verify_bounded_blob_bytes(&selection, &[b"abc"]),
            Err(S3BlobProofError::WrongLength));
    }

    #[test]
    fn invalid_metadata_and_oversized_content_never_authorize_download() {
        let (reference, mut raw) = content(b"hello");
        raw.push(b' ');
        assert_eq!(collect_bounded_blob_expectations(&[reference], &[&raw]),
            Err(S3BlobProofError::InvalidRevisionProof));
        let (mut reference, raw) = content(b"hello");
        reference.blob_bytes = Some(MAX_BLOB_BYTES as u64 + 1);
        assert_eq!(collect_bounded_blob_expectations(&[reference], &[&raw]),
            Err(S3BlobProofError::PreviewLimit));
    }

    #[test]
    fn duplicate_content_is_fetched_once_and_conflicting_sizes_rejected() {
        let (r1, raw1) = content(b"same");
        let (r2, raw2) = content(b"same");
        let selection = collect_bounded_blob_expectations(
            &[r1.clone(), r2.clone()], &[&raw1, &raw2],
        ).unwrap();
        assert_eq!(selection.len(), 1);
        assert_eq!(verify_bounded_blob_bytes(&selection, &[b"same"]).unwrap().blobs_checked, 1);
        let mut wrong = r2;
        wrong.blob_bytes = Some(99);
        assert_eq!(collect_bounded_blob_expectations(
            &[r1, wrong], &[&raw1, &raw2],
        ), Err(S3BlobProofError::InvalidRevisionProof));
    }

    #[test]
    fn tombstone_only_is_valid_empty_content_preview() {
        let record = RevisionRecord {
            id: RevisionId::new(), document_id: DocumentId::new(), parents: vec![],
            operation: RevisionOperation::Tombstone {
                path: SyncRelativePath::parse("removed.md").unwrap(),
            },
        };
        let raw = serde_json::to_vec(&record).unwrap();
        let reference = S3RevisionRefV1 {
            revision_id: record.id, document_id: record.document_id,
            revision_blake3: blake3::hash(&raw).to_hex().to_string(),
            blob_bytes: None,
        };
        let expected = collect_bounded_blob_expectations(&[reference], &[&raw]).unwrap();
        assert!(expected.is_empty());
        assert_eq!(verify_bounded_blob_bytes(&expected, &[]).unwrap().bytes_checked, 0);
    }
}
