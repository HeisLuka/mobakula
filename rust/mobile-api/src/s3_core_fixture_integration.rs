//! Offline, non-network desktop-Core V1 fixture -> Android receive integration.
//! This is deliberately not evidence of HTTPS transport or device execution.

use aquilum_core::sync::{
    DocumentId, OperationId, RevisionId, RevisionOperation, RevisionRecord,
    S3HeadPointerV1, S3Keyspace, S3ManifestShardV1, S3ManifestV1,
    S3RevisionRefV1, SyncRelativePath, VaultId,
};
use crate::notes::NoteVault;
use crate::s3_binding_journal::{S3BindingJournal, S3BindingLookup};
use crate::s3_blobs::{
    collect_bounded_blob_expectations, verify_bounded_blob_bytes,
};
use crate::s3_checkpoint::{
    compare_untrusted_s3_head_checkpoint, S3CheckpointRelation,
};
use crate::s3_index::inspect_untrusted_sharded_index;
use crate::s3_manual_receive::{
    manually_import_one_checked_document, S3ManualReceiveError,
};
use crate::s3_receive_plan::preview_untrusted_s3_additive_receive;
use crate::s3_revisions::{
    collect_untrusted_revision_refs, inspect_untrusted_revision_metadata,
};
use crate::s3_trust::check_untrusted_genesis_chain;

const PREFIX: &str = "akula-sync/portable-fixture";
const NOTE: &[u8] = b"# Shared Core fixture\nAndroid receives an untrusted file.\n";

struct Fixture {
    ns: S3Keyspace,
    head: Vec<u8>,
    manifest: Vec<u8>,
    shard: Vec<u8>,
    revision: Vec<u8>,
    genesis: String,
    source_scope: String,
    record: RevisionRecord,
}
impl Fixture {
    fn new() -> Self {
        let ns = S3Keyspace::new(PREFIX, VaultId::new()).unwrap();
        let record = RevisionRecord {
            id: RevisionId::new(),
            document_id: DocumentId::new(),
            parents: vec![],
            operation: RevisionOperation::Content {
                path: SyncRelativePath::parse("received.md").unwrap(),
                blob_hash: blake3::hash(NOTE).to_hex().to_string(),
            },
        };
        let revision = serde_json::to_vec(&record).unwrap();
        let shard = S3ManifestShardV1::new(
            &ns, OperationId::new(), vec![S3RevisionRefV1 {
                revision_id: record.id,
                document_id: record.document_id,
                revision_blake3: blake3::hash(&revision).to_hex().to_string(),
                blob_bytes: Some(NOTE.len() as u64),
            }],
        ).unwrap();
        let manifest = S3ManifestV1::new(
            &ns, OperationId::new(), 1, None,
            vec![shard.reference(&ns).unwrap()],
        ).unwrap();
        let head = S3HeadPointerV1::new(
            &ns, 1, manifest.manifest_id, &manifest.digest(&ns).unwrap(), None,
        ).unwrap().encode(&ns).unwrap();
        let genesis = blake3::hash(&head).to_hex().to_string();
        let source_scope = blake3::hash(
            b"test:yandex:provider/bucket/prefix/vault/genesis"
        ).to_hex().to_string();
        let manifest_bytes = manifest.encode(&ns).unwrap();
        let shard_bytes = shard.encode(&ns).unwrap();
        Self {
            ns, head, manifest: manifest_bytes, shard: shard_bytes,
            revision, genesis, source_scope, record,
        }
    }
    fn assert_full_proof(&self) {
        let vault = self.ns.vault_id().to_string();
        let chain = check_untrusted_genesis_chain(
            PREFIX, &vault, &self.genesis, &self.head, &[&self.manifest],
        ).unwrap();
        assert_eq!(chain.latest_generation, 1);
        let index = inspect_untrusted_sharded_index(
            PREFIX, &vault, &self.head, &self.manifest, &[&self.shard],
        ).unwrap();
        assert_eq!(index.distinct_document_ids, 1);
        let references = collect_untrusted_revision_refs(
            PREFIX, &vault, &self.head, &self.manifest, &[&self.shard],
        ).unwrap();
        let metadata = inspect_untrusted_revision_metadata(
            PREFIX, &vault, &self.head, &self.manifest,
            &[&self.shard], &[&self.revision],
        ).unwrap();
        assert_eq!(metadata.content_revisions, 1);
        let expected = collect_bounded_blob_expectations(
            &references, &[&self.revision],
        ).unwrap();
        let proof = verify_bounded_blob_bytes(&expected, &[NOTE]).unwrap();
        assert_eq!(proof.bytes_checked, NOTE.len());
        assert!(!proof.remote_owner_verified);
    }
}

#[test]
fn shared_desktop_core_v1_to_one_android_note_and_durable_mapping() {
    let f = Fixture::new();
    f.assert_full_proof();
    let vault_id = f.ns.vault_id().to_string();
    let first = compare_untrusted_s3_head_checkpoint(
        PREFIX, &vault_id, &f.genesis, &f.source_scope, &f.head,
        &[&f.manifest], None,
    ).unwrap();
    assert_eq!(first.relation, S3CheckpointRelation::FirstObservation);
    assert!(!first.import_authorized && !first.remote_owner_verified);

    // Simulate the user explicitly pinning the complete first observation,
    // then beginning a new read-only session against the same immutable root.
    let stored = first.candidate.encode();
    let checked = compare_untrusted_s3_head_checkpoint(
        PREFIX, &vault_id, &f.genesis, &f.source_scope, &f.head,
        &[&f.manifest], Some(&stored),
    ).unwrap();
    assert_eq!(checked.relation, S3CheckpointRelation::Unchanged);

    let refs = collect_untrusted_revision_refs(
        PREFIX, &vault_id, &f.head, &f.manifest, &[&f.shard],
    ).unwrap();
    let expected = collect_bounded_blob_expectations(
        &refs, &[&f.revision],
    ).unwrap();
    let plan = preview_untrusted_s3_additive_receive(
        &[f.record.clone()], &expected, &[NOTE],
    ).unwrap();
    assert_eq!(plan.candidates.len(), 1);
    assert!(!plan.writable && !plan.remote_owner_verified);
    let candidate = &plan.candidates[0];

    let dir = tempfile::tempdir().unwrap();
    let vault = NoteVault::open(dir.path()).unwrap();
    assert!(matches!(manually_import_one_checked_document(
        &vault, &f.source_scope, candidate, NOTE, false,
    ), Err(S3ManualReceiveError::NoConsent)));
    assert_eq!(vault.list().unwrap().len(), 0);

    let receipt = manually_import_one_checked_document(
        &vault, &f.source_scope, candidate, NOTE, true,
    ).unwrap();
    assert!(receipt.imported && !receipt.synchronized);
    assert!(!receipt.remote_owner_verified);
    assert_eq!(
        vault.read(&receipt.local_note_id.to_string()).unwrap().content.as_bytes(),
        NOTE,
    );
    assert!(matches!(manually_import_one_checked_document(
        &vault, &f.source_scope, candidate, NOTE, true,
    ), Err(S3ManualReceiveError::AlreadyPreparedOrBound)));
    drop(vault);

    let vault = NoteVault::open(dir.path()).unwrap();
    assert_eq!(vault.list().unwrap().len(), 1);
    let journal = S3BindingJournal::open_existing(&vault.base)
        .unwrap().unwrap();
    assert_eq!(journal.status(&f.source_scope, f.record.document_id).unwrap(),
        S3BindingLookup::Bound);
    assert_eq!(
        vault.read(&receipt.local_note_id.to_string()).unwrap().hash,
        receipt.content_blake3,
    );
}

#[test]
fn tampered_core_fixture_never_generates_receive_candidates() {
    let f = Fixture::new();
    f.assert_full_proof();
    let vault = f.ns.vault_id().to_string();

    let mut altered_shard = f.shard.clone();
    *altered_shard.last_mut().unwrap() ^= 1;
    assert!(inspect_untrusted_sharded_index(
        PREFIX, &vault, &f.head, &f.manifest, &[&altered_shard],
    ).is_err());
    let bad_genesis = "f".repeat(64);
    assert!(check_untrusted_genesis_chain(
        PREFIX, &vault, &bad_genesis, &f.head, &[&f.manifest],
    ).is_err());
    let refs = collect_untrusted_revision_refs(
        PREFIX, &vault, &f.head, &f.manifest, &[&f.shard],
    ).unwrap();
    let expected = collect_bounded_blob_expectations(
        &refs, &[&f.revision],
    ).unwrap();
    assert!(verify_bounded_blob_bytes(&expected, &[b"changed"]).is_err());
    let mut revision = f.revision.clone();
    revision.push(b' ');
    assert!(inspect_untrusted_revision_metadata(
        PREFIX, &vault, &f.head, &f.manifest, &[&f.shard], &[&revision],
    ).is_err());
}
