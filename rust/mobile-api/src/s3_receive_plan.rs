//! Android S4d0: read-only preflight of *possible* additive Markdown notes.
//! No owner proof, local filesystem inspection, File Gate write or sync receipt.
//! This preflight is advisory. Never treat a candidate as an import permit.

use std::collections::{BTreeMap, BTreeSet};
use aquilum_core::sync::{
    DocumentId, RevisionDag, RevisionId, RevisionOperation,
};
pub use aquilum_core::sync::RevisionRecord;
use crate::s3_blobs::{
    verify_bounded_blob_bytes, S3BlobExpectation, S3BlobProofError,
};
use crate::s3_revisions::MAX_REVISION_PREVIEW;

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum S3ReceivePlanError {
    BlobProof(S3BlobProofError),
    InvalidDag,
    TooManyRevisions,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct S3ProvisionalCandidate {
    pub document_id: DocumentId,
    pub revision_id: RevisionId,
    pub blob_blake3: String,
    pub bytes: usize,
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct UntrustedS3ReceivePlan {
    /// Not compared against local NoteVault; not yet authorized to import.
    pub candidates: Vec<S3ProvisionalCandidate>,
    pub forked_documents: usize,
    pub deleted_documents: usize,
    pub unsupported_documents: usize,
    pub remote_owner_verified: bool,
    pub local_conflicts_checked: bool,
    pub writable: bool,
}

impl UntrustedS3ReceivePlan {
    pub fn needs_review(&self) -> usize {
        self.forked_documents + self.deleted_documents + self.unsupported_documents
    }
}

/// Requires one complete validated remote DAG and the exact content blobs
/// selected from its immutable revisions. Never trusts remote paths as local
/// paths; only the typed UUID can later identify an additive note proposal.
/// No current filesystem check or remote owner identity proof occurs here.
pub fn preview_untrusted_s3_additive_receive(
    revisions: &[RevisionRecord],
    expected_blobs: &[S3BlobExpectation],
    blobs: &[&[u8]],
) -> Result<UntrustedS3ReceivePlan, S3ReceivePlanError> {
    if revisions.len() > MAX_REVISION_PREVIEW {
        return Err(S3ReceivePlanError::TooManyRevisions);
    }
    verify_bounded_blob_bytes(expected_blobs, blobs)
        .map_err(S3ReceivePlanError::BlobProof)?;
    let mut dag = RevisionDag::new();
    let mut documents = BTreeSet::new();
    for revision in revisions {
        documents.insert(revision.document_id);
        dag.ingest(revision.clone()).map_err(|_| S3ReceivePlanError::InvalidDag)?;
    }
    if dag.pending_len() != 0 || dag.active_len() != revisions.len() {
        return Err(S3ReceivePlanError::InvalidDag);
    }
    let content_by_hash: BTreeMap<&str, &[u8]> = expected_blobs.iter().zip(blobs)
        .map(|(entry, data)| (entry.blake3.as_str(), *data))
        .collect();
    let mut plan = UntrustedS3ReceivePlan::default();
    for document_id in documents {
        let heads = dag.heads(document_id);
        if heads.len() != 1 {
            plan.forked_documents += 1;
            continue;
        }
        let id = *heads.iter().next().ok_or(S3ReceivePlanError::InvalidDag)?;
        let revision = dag.get(id).ok_or(S3ReceivePlanError::InvalidDag)?;
        let (path, blob_hash) = match &revision.operation {
            RevisionOperation::Content { path, blob_hash } =>
                (path.as_str(), blob_hash.as_str()),
            RevisionOperation::Rename { to, blob_hash, .. } =>
                (to.as_str(), blob_hash.as_str()),
            RevisionOperation::Tombstone { .. } => {
                // Remote tombstones are never deletion instructions on mobile.
                plan.deleted_documents += 1;
                continue;
            }
        };
        // Android V0 supports app-private, top-level Markdown notes only.
        // The path is used as a classification predicate and NEVER joined to
        // local application storage or returned to Android.
        if !path.ends_with(".md") || path.contains('/') || path.contains('\\') {
            plan.unsupported_documents += 1;
            continue;
        }
        let Some(content) = content_by_hash.get(blob_hash).copied() else {
            return Err(S3ReceivePlanError::BlobProof(S3BlobProofError::MissingBlob));
        };
        // NoteVault's File Gate normalizes CRLF. Until metadata mapping and
        // source-baseline rules exist, refuse implicit content rewrites.
        if std::str::from_utf8(content).is_err()
            || content.contains(&0) || content.contains(&b'\r')
        {
            plan.unsupported_documents += 1;
            continue;
        }
        plan.candidates.push(S3ProvisionalCandidate {
            document_id, revision_id: id,
            blob_blake3: blob_hash.to_owned(), bytes: content.len(),
        });
    }
    Ok(plan)
}

#[cfg(test)]
mod tests {
    use super::*;
    use aquilum_core::sync::SyncRelativePath;

    fn content(
        document_id: DocumentId, parents: Vec<RevisionId>,
        path: &str, body: &[u8],
    ) -> RevisionRecord {
        RevisionRecord {
            id: RevisionId::new(), document_id, parents,
            operation: RevisionOperation::Content {
                path: SyncRelativePath::parse(path).unwrap(),
                blob_hash: blake3::hash(body).to_hex().to_string(),
            },
        }
    }

    fn preview(
        revs: Vec<RevisionRecord>, bodies: Vec<Vec<u8>>,
    ) -> Result<UntrustedS3ReceivePlan, S3ReceivePlanError> {
        let mut hash_map = BTreeMap::<String, Vec<u8>>::new();
        for b in bodies {
            hash_map.insert(blake3::hash(&b).to_hex().to_string(), b);
        }
        let expectations: Vec<_> = hash_map.iter().map(|(hash, body)|
            S3BlobExpectation { blake3: hash.clone(), bytes: body.len() }
        ).collect();
        let body_refs: Vec<&[u8]> = hash_map.values().map(Vec::as_slice).collect();
        preview_untrusted_s3_additive_receive(&revs, &expectations, &body_refs)
    }

    #[test]
    fn exact_single_markdown_head_is_only_a_provisional_candidate() {
        let doc = DocumentId::new();
        let rev = content(doc, vec![], "note.md", b"# Note\n");
        let result = preview(vec![rev.clone()], vec![b"# Note\n".to_vec()]).unwrap();
        assert_eq!(result.candidates.len(), 1);
        assert_eq!(result.candidates[0].document_id, doc);
        assert_eq!(result.candidates[0].revision_id, rev.id);
        assert_eq!(result.needs_review(), 0);
        assert!(!result.remote_owner_verified);
        assert!(!result.local_conflicts_checked);
        assert!(!result.writable);
    }

    #[test]
    fn latest_child_wins_without_reimporting_historical_ancestors() {
        let doc = DocumentId::new();
        let ancestor = content(doc, vec![], "note.md", b"old");
        let newer = content(doc, vec![ancestor.id], "note.md", b"new");
        let result = preview(vec![newer.clone(), ancestor],
            vec![b"new".to_vec(), b"old".to_vec()]).unwrap();
        assert_eq!(result.candidates.len(), 1);
        assert_eq!(result.candidates[0].revision_id, newer.id);
    }

    #[test]
    fn fork_and_tombstone_are_review_not_automatic_import_or_delete() {
        let doc = DocumentId::new();
        let a = content(doc, vec![], "note.md", b"a");
        let b = content(doc, vec![], "note.md", b"b");
        let result = preview(vec![a.clone(), b],
            vec![b"a".to_vec(), b"b".to_vec()]).unwrap();
        assert!(result.candidates.is_empty());
        assert_eq!(result.forked_documents, 1);
        let tombstone = RevisionRecord {
            id: RevisionId::new(), document_id: doc, parents: vec![a.id],
            operation: RevisionOperation::Tombstone {
                path: SyncRelativePath::parse("note.md").unwrap(),
            },
        };
        let result = preview(vec![a, tombstone], vec![b"a".to_vec()]).unwrap();
        assert!(result.candidates.is_empty());
        assert_eq!(result.deleted_documents, 1);
    }

    #[test]
    fn nested_non_markdown_binary_crlf_and_nul_are_not_candidates() {
        let cases: &[(&str, &[u8])] = &[
            ("nested/note.md", b"ok"), ("picture.png", b"ok"),
            ("note.md", b"\xff\xfe"), ("note.md", b"a\r\nb"),
            ("note.md", b"ok\x00not-text"),
        ];
        for (path, bytes) in cases {
            let rev = content(DocumentId::new(), vec![], path, bytes);
            let result = preview(vec![rev], vec![bytes.to_vec()]).unwrap();
            assert_eq!(result.candidates.len(), 0);
            assert_eq!(result.unsupported_documents, 1);
        }
    }

    #[test]
    fn missing_blob_and_unresolved_parents_never_create_candidates() {
        let doc = DocumentId::new();
        let rev = content(doc, vec![RevisionId::new()], "note.md", b"ok");
        assert_eq!(preview(vec![rev], vec![b"ok".to_vec()]),
            Err(S3ReceivePlanError::InvalidDag));
        let rev = content(doc, vec![], "note.md", b"ok");
        assert_eq!(preview(vec![rev], vec![]),
            Err(S3ReceivePlanError::BlobProof(S3BlobProofError::MissingBlob)));
    }

    #[test]
    fn empty_index_and_valid_empty_markdown_are_distinguished() {
        assert_eq!(preview(vec![], vec![]).unwrap().candidates.len(), 0);
        let rev = content(DocumentId::new(), vec![], "empty.md", b"");
        let result = preview(vec![rev], vec![vec![]]).unwrap();
        assert_eq!(result.candidates.len(), 1);
        assert_eq!(result.candidates[0].bytes, 0);
    }
}
