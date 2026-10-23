//! Read-only comparison against app-owned note slots. No trust or write grants.
//! Requires a completed immutable S3 blob proof in the SAME native session.

use jni::{JNIEnv, objects::{JByteArray, JClass, JString}};
use jni::sys::jstring;
use serde_json::{Value, json};
use mobakula_mobile_api::notes::S3LocalSlotState;
use crate::VAULT;
use crate::s3_read_session::{fault, jtext, read_array, result_string, with_session};

/// A remote UUID can at most select an advisory local slot. Matching bytes do
/// not establish matching provenance and never authorize importing/updating.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_heisluka_mobakula_NativeBridge_inspectS3LocalSlots(
    mut env: JNIEnv, _receiver: JClass,
    token: JString, head: JByteArray,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let id = jtext(&mut env, &token)?;
        let root = read_array(&env, &head, 4096)?;
        with_session(&id, |slot| {
            let digest = ring::digest::digest(&ring::digest::SHA256, &root);
            if slot.proven_blobs_head_sha256.as_ref().map(|x| x.as_slice())
                != Some(digest.as_ref())
            {
                return Err("unverified_remote_blobs");
            }
            let Some(vault_lock) = VAULT.get() else {
                return Ok(unavailable());
            };
            let locked = vault_lock.lock().map_err(|_| "vault_state")?;
            let Some(vault) = locked.as_ref() else {
                return Ok(unavailable());
            };
            let mut absent = 0usize;
            let mut identical_unbound = 0usize;
            let mut conflicting = 0usize;
            let mut unsafe_entry = 0usize;
            for candidate in &slot.provisional_candidates {
                match vault.inspect_untrusted_s3_slot(
                    candidate.document_id, &candidate.blob_blake3
                ).map_err(|_| "local_slot_read_failed")? {
                    S3LocalSlotState::Absent => absent += 1,
                    S3LocalSlotState::SameContentUnbound =>
                        identical_unbound += 1,
                    S3LocalSlotState::DifferentContent => conflicting += 1,
                    S3LocalSlotState::UnsafeEntry => unsafe_entry += 1,
                }
            }
            Ok(json!({"ok":true,"data":{
                "localSlotsChecked":true,
                "slotsExamined":slot.provisional_candidates.len(),
                "absent":absent,
                "identicalButUnbound":identical_unbound,
                "conflicting":conflicting,
                "unsafe":unsafe_entry,
                "localIdentityBound":false,
                "importAuthorized":false,"remoteOwnerVerified":false,"synced":false
            }}))
        })
    }));
    result_string(env, match result {
        Ok(Ok(value)) => value, Ok(Err(code)) => fault(code),
        Err(_) => fault("panic"),
    })
}

fn unavailable() -> Value {
    json!({"ok":true,"data":{
        "localSlotsChecked":false,
        "slotsExamined":0,
        "absent":0,"identicalButUnbound":0,"conflicting":0,"unsafe":0,
        "localIdentityBound":false,
        "importAuthorized":false,"remoteOwnerVerified":false,"synced":false
    }})
}
