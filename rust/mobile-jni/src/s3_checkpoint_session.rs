//! S4d2a: read-only local checkpoint comparison, never publisher authority.
//! A mismatch revokes access to the index and all later blob reads.

use jni::{JNIEnv, objects::{JByteArray, JClass, JObjectArray, JString}};
use jni::sys::jstring;
use mobakula_mobile_api::s3_checkpoint::{
    compare_untrusted_s3_head_checkpoint, S3CheckpointRelation,
};
use ring::digest;
use serde_json::json;
use crate::s3_read_session::{
    fault, jtext, read_array, result_string, with_session,
};

const MAX_CHAIN: usize = 128;
const MAX_MANIFEST_BYTES: usize = 512 * 1024;
const MAX_CHAIN_BYTES: usize = 8 * 1024 * 1024;
const MAX_ANCHOR_BYTES: usize = 512;

/// Requires a previously verified canonical genesis chain in this native
/// session; rejects any rollback, fork or mismatched source scope. A missing
/// local anchor is explicitly a first observation, NEVER ownership proof.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_heisluka_mobakula_NativeBridge_checkS3CheckpointProbe(
    mut env: JNIEnv, _receiver: JClass, token: JString, head: JByteArray,
    manifests: JObjectArray, sealed_prior: JByteArray,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let token = jtext(&mut env, &token)?;
        let head = read_array(&env, &head, 4096)?;
        let n = env.get_array_length(&manifests).map_err(|_| "jni")? as usize;
        if n == 0 || n > MAX_CHAIN { return Err("chain_bounds"); }
        let mut chain = Vec::with_capacity(n);
        let mut total = 0usize;
        for i in 0..n {
            let next = env.get_object_array_element(&manifests, i as i32)
                .map_err(|_| "jni")?;
            let bytes = read_array(&env, &JByteArray::from(next), MAX_MANIFEST_BYTES)?;
            total = total.checked_add(bytes.len()).ok_or("chain_bounds")?;
            if total > MAX_CHAIN_BYTES { return Err("chain_bounds"); }
            chain.push(bytes);
        }
        let pin_len = env.get_array_length(&sealed_prior).map_err(|_| "jni")? as usize;
        if pin_len > MAX_ANCHOR_BYTES { return Err("checkpoint_bounds"); }
        let old = if pin_len == 0 {
            None
        } else {
            Some(read_array(&env, &sealed_prior, MAX_ANCHOR_BYTES)?)
        };
        with_session(&token, |slot| {
            slot.revoke_after_checkpoint_attempt();
            let digest = digest::digest(&digest::SHA256, &head);
            if slot.ancestry_head_sha256.as_ref().map(|v| v.as_slice())
                != Some(digest.as_ref())
            { return Err("unverified_genesis"); }
            let chain_refs: Vec<&[u8]> = chain.iter().map(Vec::as_slice).collect();
            let proof = compare_untrusted_s3_head_checkpoint(
                slot.signer.prefix(), &slot.signer.cloud_vault_id(),
                slot.signer.genesis_fingerprint(),
                &slot.signer.checkpoint_scope_fingerprint(), &head,
                &chain_refs, old.as_deref(),
            ).map_err(|_| "s3_checkpoint_rejected")?;
            let mut identity = [0_u8;32];
            identity.copy_from_slice(digest.as_ref());
            slot.checkpoint_head_sha256 = Some(identity);
            slot.checkpoint_was_pinned = matches!(proof.relation, S3CheckpointRelation::Unchanged);
            let relation = match proof.relation {
                S3CheckpointRelation::FirstObservation => "first",
                S3CheckpointRelation::Unchanged => "unchanged",
                S3CheckpointRelation::Advanced => "advanced",
            };
            let canonical = String::from_utf8(proof.candidate.encode())
                .map_err(|_| "checkpoint_encoding")?;
            Ok(json!({"ok":true,"data":{
                "relation":relation,
                "checkpointCandidate":canonical,
                "sourceScope":proof.candidate.scope_fingerprint,
                "hadLocalCheckpoint":old.is_some(),
                "remoteOwnerVerified":false,
                "importAuthorized":false,"synced":false
            }}))
        })
    }));
    result_string(env, match result {
        Ok(Ok(value)) => value, Ok(Err(code)) => fault(code),
        Err(_) => fault("panic"),
    })
}
