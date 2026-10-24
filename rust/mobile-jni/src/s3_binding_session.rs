//! S4d3: read-only inspection of durable source-scoped binding intents.
//! S3 paths and remote-supplied local UUIDs never enter this interface.

use jni::{JNIEnv, objects::{JByteArray, JClass, JString}};
use jni::sys::jstring;
use mobakula_mobile_api::s3_binding_journal::{
    S3BindingJournal, S3BindingPhase,
};
use serde_json::{Value, json};
use crate::VAULT;
use crate::s3_read_session::{
    fault, jtext, read_array, result_string, with_session,
};

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_heisluka_mobakula_NativeBridge_inspectS3BindingJournalProbe(
    mut env: JNIEnv, _receiver: JClass, session: JString, head: JByteArray,
) -> jstring {
    let answer = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let token = jtext(&mut env, &session)?;
        let head = read_array(&env, &head, 4096)?;
        with_session(&token, |slot| {
            let digest = ring::digest::digest(&ring::digest::SHA256, &head);
            if slot.checkpoint_head_sha256.as_ref().map(|v| v.as_slice())
                != Some(digest.as_ref())
                || slot.proven_blobs_head_sha256.as_ref().map(|v| v.as_slice())
                    != Some(digest.as_ref())
            {
                return Err("unverified_s3_binding_source");
            }
            let lock = match VAULT.get() {
                Some(lock) => lock,
                None => return Ok(unavailable()),
            };
            let guard = lock.lock().map_err(|_| "vault_state")?;
            let vault = match guard.as_ref() {
                Some(vault) => vault,
                None => return Ok(unavailable()),
            };
            let journal = S3BindingJournal::open_existing(&vault.base)
                .map_err(|_| "invalid_binding_journal")?;
            let scope = slot.signer.checkpoint_scope_fingerprint();
            let mut exact = 0_usize;
            let mut outdated = 0_usize;
            let mut prepared = 0_usize;
            let mut held = 0_usize;
            let mut unmapped = 0_usize;
            for candidate in &slot.provisional_candidates {
                let binding = journal.as_ref()
                    .map(|db| db.lookup(&scope, candidate.document_id))
                    .transpose().map_err(|_| "binding_lookup_error")?
                    .flatten();
                match binding {
                    None => unmapped += 1,
                    Some(binding) if binding.phase == S3BindingPhase::Hold => held += 1,
                    Some(binding) if binding.phase == S3BindingPhase::Prepared
                        && binding.revision_id == candidate.revision_id
                        && binding.blob_blake3 == candidate.blob_blake3 => prepared += 1,
                    Some(binding) if binding.phase == S3BindingPhase::Bound
                        && binding.revision_id == candidate.revision_id
                        && binding.blob_blake3 == candidate.blob_blake3 => {
                        // Durable mapping alone is not a claim that the
                        // locally editable file still contains imported bytes.
                        match vault.read(&binding.local_note_id.to_string()) {
                            Ok(local) if local.hash == candidate.blob_blake3 => exact += 1,
                            _ => held += 1,
                        }
                    },
                    Some(binding) if binding.phase == S3BindingPhase::Bound => outdated += 1,
                    Some(_) => held += 1,
                }
            }
            Ok(json!({"ok":true,"data":{
                "bindingScanAvailable":true,
                "journalExists":journal.is_some(),
                "scannedDocuments":slot.provisional_candidates.len(),
                "exactBindings":exact,"outdatedBindings":outdated,
                "preparedIntents":prepared,"onHold":held,"unmapped":unmapped,
                "remoteOwnerVerified":false,"importAuthorized":false,"synced":false
            }}))
        })
    }));
    result_string(env, match answer {
        Ok(Ok(value)) => value, Ok(Err(code)) => fault(code),
        Err(_) => fault("panic"),
    })
}

fn unavailable() -> Value {
    json!({"ok":true,"data":{
        "bindingScanAvailable":false,"journalExists":false,
        "scannedDocuments":0,"exactBindings":0,"outdatedBindings":0,
        "preparedIntents":0,"onHold":0,"unmapped":0,
        "remoteOwnerVerified":false,"importAuthorized":false,"synced":false
    }})
}
