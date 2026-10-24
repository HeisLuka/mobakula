//! S4d4: explicit, one-shot *external Markdown import*, not synchronization.
//! This JNI entrypoint has no arbitrary filesystem path or remote S3 write.
//! Kotlin must recheck the entire root and content before invoking it.

use jni::{JNIEnv, objects::{JByteArray, JClass, JString}};
use jni::sys::jstring;
use mobakula_mobile_api::s3_manual_receive::{
    manually_import_one_checked_document, S3ManualReceiveError,
};
use serde_json::json;
use zeroize::Zeroizing;
use crate::VAULT;
use crate::s3_read_session::{
    fault, jtext, read_array, result_string, with_session,
};

const MAX_IMPORT_BYTES: usize = 1024 * 1024;

/// Called ONLY by a fresh explicit user-confirmation button after the
/// Android transport reverified all content and previously-pinned S3 head.
/// Session expires in 180s and contains the exact vetted candidate allowlist.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_heisluka_mobakula_NativeBridge_importS3VerifiedMarkdown(
    mut env: JNIEnv, _receiver: JClass, session: JString,
    head: JByteArray, document: JString, payload: JByteArray,
) -> jstring {
    let answer = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let token = jtext(&mut env, &session)?;
        let head = read_array(&env, &head, 4096)?;
        let document = jtext(&mut env, &document)?;
        if document.len() != 36 { return Err("invalid_document_id"); }
        let len = env.get_array_length(&payload).map_err(|_| "jni")? as usize;
        if len > MAX_IMPORT_BYTES { return Err("oversized_import"); }
        let mut signed = Zeroizing::new(vec![0i8; len]);
        if len != 0 {
            env.get_byte_array_region(&payload, 0, &mut signed)
                .map_err(|_| "jni")?;
        }
        let raw = Zeroizing::new(signed.iter().map(|v| *v as u8).collect::<Vec<u8>>());
        with_session(&token, |slot| {
            let digest = ring::digest::digest(&ring::digest::SHA256, &head);
            if !slot.checkpoint_was_pinned
                || slot.checkpoint_head_sha256.as_ref().map(|v| v.as_slice())
                    != Some(digest.as_ref())
                || slot.proven_blobs_head_sha256.as_ref().map(|v| v.as_slice())
                    != Some(digest.as_ref())
            {
                return Err("requires_pinned_verified_root");
            }
            let candidate = slot.provisional_candidates.iter()
                .find(|c| c.document_id.to_string() == document)
                .ok_or("document_not_in_verified_snapshot")?.clone();
            if candidate.bytes != raw.len()
                || blake3::hash(&raw).to_hex().as_str() != candidate.blob_blake3
            {
                return Err("payload_does_not_match_verified_revision");
            }
            let scope = slot.signer.checkpoint_scope_fingerprint();
            let lock = VAULT.get().ok_or("vault_not_ready")?;
            let guard = lock.lock().map_err(|_| "vault_state")?;
            let vault = guard.as_ref().ok_or("vault_not_ready")?;
            match manually_import_one_checked_document(
                vault, &scope, &candidate, &raw, true,
            ) {
                Ok(receipt) => Ok(json!({"ok":true,"data":{
                    "imported":receipt.imported,
                    "localNoteId":receipt.local_note_id.to_string(),
                    "documentId":receipt.document_id.to_string(),
                    "sourceScope":receipt.source_scope,
                    "remoteOwnerVerified":false,"synced":false
                }})),
                Err(S3ManualReceiveError::AlreadyPreparedOrBound) =>
                    Err("existing_binding_needs_review"),
                Err(S3ManualReceiveError::NeedsReview { local_note_id }) =>
                    Ok(json!({"ok":false,"code":"receive_needs_review",
                        "localNoteId":local_note_id.to_string(),
                        "message":"File Gate may have committed; inspect, do not retry"})),
                Err(_) => Err("receive_rejected"),
            }
        })
    }));
    result_string(env, match answer {
        Ok(Ok(value)) => value, Ok(Err(code)) => fault(code),
        Err(_) => fault("panic"),
    })
}
