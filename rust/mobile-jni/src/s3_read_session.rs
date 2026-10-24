//! Native-only short-lived credential scope for Android HEAD/GET S3 proof.
//! Neither the ordinary note JSON API nor app-owned files store credentials.
//! The Kotlin HTTP adapter only receives short-lived *signed requests*;
//! this module never calls the network or marks a note as synchronized.

use jni::{JNIEnv, objects::{JByteArray, JCharArray, JClass, JObjectArray, JString}};
use jni::sys::jstring;
use mobakula_mobile_api::s3_readonly::{
    EMPTY_SHA256, S3ReadObject, S3ReadSigner, parse_manifest_id,
    parse_revision_id,
};
use mobakula_mobile_api::s3_trust::check_untrusted_genesis_chain;
use mobakula_mobile_api::s3_index::inspect_untrusted_sharded_index;
use mobakula_mobile_api::s3_revisions::{
    collect_untrusted_revision_refs, inspect_untrusted_revision_records,
    MAX_REVISION_PREVIEW, MAX_REVISION_JSON_BYTES,
};
use mobakula_mobile_api::s3_blobs::{
    collect_bounded_blob_expectations, S3BlobExpectation, S3BlobProofError,
};
use mobakula_mobile_api::s3_revisions::S3RevisionRefV1;
use mobakula_mobile_api::s3_receive_plan::{RevisionRecord, S3ProvisionalCandidate};
use ring::rand::{SecureRandom, SystemRandom};
use serde_json::{Value, json};
use std::{ptr, sync::{Mutex, OnceLock}, time::{Duration, Instant}};
use zeroize::Zeroizing;

const MAX_INVITE: usize = 16 * 1024;
const MAX_PASSWORD: usize = 1024;
const MAX_MANIFESTS: usize = 128;
const MAX_MANIFEST_BYTES: usize = 512 * 1024;
const MAX_INDEX_SHARDS: usize = 16;
const MAX_INDEX_SHARD_BYTES: usize = 128 * 1024;
const MAX_CHAIN_BYTES: usize = 8 * 1024 * 1024;
pub(super) const MAX_READ_SIGNATURES: usize = 768;
const SESSION_TTL: Duration = Duration::from_secs(180);

pub(super) struct Session {
    id: String,
    pub(super) signer: S3ReadSigner,
    expires_at: Instant,
    pub(super) signatures: usize,
    pub(super) ancestry_head_sha256: Option<[u8;32]>,
    pub(super) checkpoint_head_sha256: Option<[u8;32]>,
    revision_index_head_sha256: Option<[u8;32]>,
    revision_refs: Vec<S3RevisionRefV1>,
    pub(super) blob_head_sha256: Option<[u8;32]>,
    pub(super) blob_expectations: Vec<S3BlobExpectation>,
    pub(super) receive_records: Vec<RevisionRecord>,
    pub(super) proven_blobs_head_sha256: Option<[u8;32]>,
    pub(super) provisional_candidates: Vec<S3ProvisionalCandidate>,
}
impl Session {
    /// A failed/repeated checkpoint comparison cannot reuse earlier
    /// index, revision, blob, or local-collision authorizations.
    pub(super) fn revoke_after_checkpoint_attempt(&mut self) {
        self.checkpoint_head_sha256 = None;
        self.revision_index_head_sha256 = None;
        self.revision_refs.clear();
        self.blob_head_sha256 = None;
        self.blob_expectations.clear();
        self.receive_records.clear();
        self.proven_blobs_head_sha256 = None;
        self.provisional_candidates.clear();
    }
}
static SESSION: OnceLock<Mutex<Option<Session>>> = OnceLock::new();

fn session_slot() -> &'static Mutex<Option<Session>> {
    SESSION.get_or_init(|| Mutex::new(None))
}

pub(super) fn fault(code: &str) -> Value {
    json!({"ok":false,"code":code,"message":"S3 read-only probe rejected"})
}
pub(super) fn result_string(mut env: JNIEnv, response: Value) -> jstring {
    env.new_string(response.to_string())
        .map_or(ptr::null_mut(), |value| value.into_raw())
}
pub(super) fn jtext(env: &mut JNIEnv, input: &JString) -> Result<String, &'static str> {
    env.get_string(input).map(|s| s.to_string_lossy().into_owned())
        .map_err(|_| "jni")
}
pub(super) fn read_array(env: &JNIEnv, array: &JByteArray, max: usize) -> Result<Vec<u8>, &'static str> {
    let len = env.get_array_length(array).map_err(|_| "jni")? as usize;
    if len == 0 || len > max { return Err("too_large"); }
    let mut signed = vec![0i8; len];
    env.get_byte_array_region(array, 0, &mut signed).map_err(|_| "jni")?;
    Ok(signed.iter().map(|b| *b as u8).collect())
}
fn password_chars(env: &JNIEnv, array: &JCharArray) -> Result<Zeroizing<String>, &'static str> {
    let len = env.get_array_length(array).map_err(|_| "jni")? as usize;
    if len == 0 || len > MAX_PASSWORD { return Err("too_large"); }
    let mut utf16 = Zeroizing::new(vec![0u16; len]);
    env.get_char_array_region(array, 0, &mut utf16).map_err(|_| "jni")?;
    let str_value = String::from_utf16(&utf16).map_err(|_| "invalid_utf16")?;
    Ok(Zeroizing::new(str_value))
}
fn new_id() -> Result<String, &'static str> {
    let mut nonce = [0u8; 16];
    SystemRandom::new().fill(&mut nonce).map_err(|_| "random")?;
    let mut id = String::with_capacity(32);
    for b in nonce { id.push_str(&format!("{b:02x}")); }
    Ok(id)
}
pub(super) fn with_session<T>(
    id: &str, callback: impl FnOnce(&mut Session) -> Result<T, &'static str>,
) -> Result<T, &'static str> {
    let mut guard = session_slot().lock().map_err(|_| "state")?;
    let Some(ref mut session) = *guard else { return Err("no_session"); };
    if session.expires_at <= Instant::now() {
        *guard = None; // immediately drop native credentials
        return Err("session_expired");
    }
    if session.id != id { return Err("wrong_session"); }
    callback(session)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_heisluka_mobakula_NativeBridge_beginS3ReadProbe(
    env: JNIEnv, _receiver: JClass,
    encrypted: JByteArray, password: JCharArray,
) -> jstring {
    let response = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let bytes = read_array(&env, &encrypted, MAX_INVITE)?;
        let pass = password_chars(&env, &password)?;
        let signer = S3ReadSigner::from_encrypted_invitation(&bytes, &pass)
            .map_err(|_| "invalid_invitation")?;
        let id = new_id()?;
        let scope = json!({
            "session":id,"cloudVaultId":signer.cloud_vault_id(),
            "prefix":signer.prefix(),"headKey":signer.current_head_key(),
            "claimedGenesisFingerprint":signer.genesis_fingerprint(),
            "provider":"yandex","trust":false,"synced":false
        });
        let mut guard = session_slot().lock().map_err(|_| "state")?;
        *guard = Some(Session {
            id, signer, expires_at: Instant::now() + SESSION_TTL,
            signatures: 0,
            ancestry_head_sha256: None,
            checkpoint_head_sha256: None,
            revision_index_head_sha256: None,
            revision_refs: Vec::new(),
            blob_head_sha256: None,
            blob_expectations: Vec::new(),
            receive_records: Vec::new(),
            proven_blobs_head_sha256: None,
            provisional_candidates: Vec::new(),
        });
        Ok::<Value, &'static str>(json!({"ok":true,"data":scope}))
    }));
    result_string(env, match response {
        Ok(Ok(value)) => value, Ok(Err(code)) => fault(code),
        Err(_) => fault("panic"),
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_heisluka_mobakula_NativeBridge_signS3ReadProbe(
    mut env: JNIEnv, _receiver: JClass,
    session: JString, method: JString, manifest_id: JString,
    if_match: JString, utc_stamp: JString,
) -> jstring {
    let response = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let id = jtext(&mut env, &session)?;
        let method = jtext(&mut env, &method)?;
        let manifest_id = jtext(&mut env, &manifest_id)?;
        let if_match = jtext(&mut env, &if_match)?;
        let stamp = jtext(&mut env, &utc_stamp)?;
        with_session(&id, |slot| {
            if slot.signatures >= MAX_READ_SIGNATURES { return Err("quota"); }
            let object = if manifest_id.is_empty() {
                S3ReadObject::CurrentHead
            } else {
                parse_manifest_id(&manifest_id).map_err(|_| "invalid_manifest_id")?
            };
            let signed = slot.signer.sign(
                object, &method, &stamp,
                if if_match.is_empty() { None } else { Some(&if_match) },
            ).map_err(|_| "invalid_signature_request")?;
            slot.signatures += 1;
            Ok(json!({"ok":true,"data":{
                "url":signed.url,"method":signed.method,
                "authorization":signed.authorization,
                "amzDate":signed.amz_date,"payloadSha256":EMPTY_SHA256,
                "ifMatch":signed.if_match
            }}))
        })
    }));
    result_string(env, match response {
        Ok(Ok(value)) => value, Ok(Err(code)) => fault(code),
        Err(_) => fault("panic"),
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_heisluka_mobakula_NativeBridge_verifyS3ReadProbe(
    mut env: JNIEnv, _receiver: JClass,
    session: JString, head: JByteArray, manifests: JObjectArray,
) -> jstring {
    let response = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let id = jtext(&mut env, &session)?;
        let head = read_array(&env, &head, 4096)?;
        let count = env.get_array_length(&manifests).map_err(|_| "jni")? as usize;
        if count == 0 || count > MAX_MANIFESTS { return Err("chain_bounds"); }
        let mut chain = Vec::<Vec<u8>>::with_capacity(count);
        let mut total = 0usize;
        for i in 0..count {
            let next = env.get_object_array_element(&manifests, i as i32)
                .map_err(|_| "jni")?;
            let arr = JByteArray::from(next);
            let data = read_array(&env, &arr, MAX_MANIFEST_BYTES)?;
            total = total.checked_add(data.len()).ok_or("chain_bounds")?;
            if total > MAX_CHAIN_BYTES { return Err("chain_bounds"); }
            chain.push(data);
        }
        with_session(&id, |slot| {
            // A new ancestry attempt invalidates all earlier index authority.
            slot.ancestry_head_sha256 = None;
            slot.checkpoint_head_sha256 = None;
            slot.revision_index_head_sha256 = None;
            slot.revision_refs.clear();
            slot.blob_head_sha256 = None;
            slot.blob_expectations.clear();
            slot.receive_records.clear();
            slot.proven_blobs_head_sha256 = None;
            slot.provisional_candidates.clear();
            let slices: Vec<&[u8]> = chain.iter().map(Vec::as_slice).collect();
            let proof = check_untrusted_genesis_chain(
                slot.signer.prefix(), &slot.signer.cloud_vault_id(),
                slot.signer.genesis_fingerprint(), &head, &slices,
            ).map_err(|_| "invalid_ancestry")?;
            // The index viewer must use the exact root that passed ancestry.
            let digest = ring::digest::digest(&ring::digest::SHA256, &head);
            let mut identity = [0u8;32];
            identity.copy_from_slice(digest.as_ref());
            slot.ancestry_head_sha256 = Some(identity);
            Ok(json!({"ok":true,"data":{
                "generation":proof.latest_generation,
                "revisions":proof.latest_revisions,
                "manifestsChecked":proof.immutable_manifests_checked,
                "bytesOnly":true,"remoteOwnerVerified":false,"synced":false
            }}))
        })
    }));
    result_string(env, match response {
        Ok(Ok(value)) => value, Ok(Err(code)) => fault(code),
        Err(_) => fault("panic"),
    })
}

/// Strict read-only index verification; requires an ancestry proof in this
/// same native session for exactly the same head bytes. No File Gate writes.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_heisluka_mobakula_NativeBridge_verifyS3IndexProbe(
    mut env: JNIEnv, _receiver: JClass,
    token: JString, head: JByteArray, latest: JByteArray, shards: JObjectArray,
) -> jstring {
    let response = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let id = jtext(&mut env, &token)?;
        let head = read_array(&env, &head, 4096)?;
        let newest = read_array(&env, &latest, MAX_MANIFEST_BYTES)?;
        let count = env.get_array_length(&shards).map_err(|_| "jni")? as usize;
        if count > MAX_INDEX_SHARDS { return Err("too_many_shards"); }
        let mut payload = Vec::with_capacity(count);
        let mut total = 0usize;
        for i in 0..count {
            let item = env.get_object_array_element(&shards, i as i32)
                .map_err(|_| "jni")?;
            let array = JByteArray::from(item);
            let bytes = read_array(&env, &array, MAX_INDEX_SHARD_BYTES)?;
            total = total.checked_add(bytes.len()).ok_or("shard_bounds")?;
            if total > MAX_INDEX_SHARD_BYTES * MAX_INDEX_SHARDS {
                return Err("shard_bounds");
            }
            payload.push(bytes);
        }
        with_session(&id, |session| {
            session.revision_refs.clear();
            session.revision_index_head_sha256 = None;
            session.blob_head_sha256 = None;
            session.blob_expectations.clear();
            session.receive_records.clear();
            session.proven_blobs_head_sha256 = None;
            session.provisional_candidates.clear();
            let digest = ring::digest::digest(&ring::digest::SHA256, &head);
            // No remote index or later blob access until S4d2a has checked
            // the existing local anchor against this exact verified root.
            if session.ancestry_head_sha256.as_ref().map(|x| x.as_slice())
                != Some(digest.as_ref())
                || session.checkpoint_head_sha256.as_ref().map(|x| x.as_slice())
                    != Some(digest.as_ref())
            {
                return Err("unchecked_local_checkpoint");
            }
            let slices: Vec<&[u8]> = payload.iter().map(Vec::as_slice).collect();
            let index = inspect_untrusted_sharded_index(
                session.signer.prefix(), &session.signer.cloud_vault_id(),
                &head, &newest, &slices,
            ).map_err(|_| "invalid_sharded_index")?;
            // For bounded indices, bind every canonical revision UUID to this
            // exact-root proof. Other indices remain count-only, not empty.
            let can_read_revisions = index.revisions <= MAX_REVISION_PREVIEW;
            let revision_ids = if can_read_revisions {
                let refs = collect_untrusted_revision_refs(
                    session.signer.prefix(), &session.signer.cloud_vault_id(),
                    &head, &newest, &slices,
                ).map_err(|_| "invalid_revision_index")?;
                let ids: Vec<String> = refs.iter().map(|r| r.revision_id.to_string()).collect();
                let mut identity = [0u8;32];
                identity.copy_from_slice(digest.as_ref());
                session.revision_index_head_sha256 = Some(identity);
                session.revision_refs = refs;
                ids
            } else {
                Vec::new()
            };
            Ok(json!({"ok":true,"data":{
                "revisions":index.revisions,
                "documents":index.distinct_document_ids,
                "shardsChecked":index.shards_checked,
                "revisionIds":revision_ids,
                "metadataPreviewSupported":can_read_revisions,
                "bytesOnly":true,"remoteOwnerVerified":false,"synced":false
            }}))
        })
    }));
    result_string(env, match response {
        Ok(Ok(value)) => value, Ok(Err(code)) => fault(code),
        Err(_) => fault("panic"),
    })
}

/// Sign only immutable revisions selected by the exact-root verified index
/// in this native session. Never expose a general-purpose object signer.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_heisluka_mobakula_NativeBridge_signS3RevisionProbe(
    mut env: JNIEnv, _receiver: JClass,
    session: JString, revision_id: JString, method: JString,
    if_match: JString, utc_stamp: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let token = jtext(&mut env, &session)?;
        let revision_id = jtext(&mut env, &revision_id)?;
        let method = jtext(&mut env, &method)?;
        let if_match = jtext(&mut env, &if_match)?;
        let stamp = jtext(&mut env, &utc_stamp)?;
        let id = parse_revision_id(&revision_id).map_err(|_| "invalid_revision_id")?;
        with_session(&token, |slot| {
            if slot.signatures >= MAX_READ_SIGNATURES { return Err("quota"); }
            if slot.revision_index_head_sha256.is_none()
                || !slot.revision_refs.iter().any(|reference| reference.revision_id == id)
            {
                return Err("revision_not_in_verified_index");
            }
            let signed = slot.signer.sign(
                S3ReadObject::ImmutableRevision(id), &method, &stamp,
                if if_match.is_empty() { None } else { Some(&if_match) },
            ).map_err(|_| "invalid_signature_request")?;
            slot.signatures += 1;
            Ok(json!({"ok":true,"data":{
                "url":signed.url,"method":signed.method,
                "authorization":signed.authorization,
                "amzDate":signed.amz_date,"payloadSha256":EMPTY_SHA256,
                "ifMatch":signed.if_match
            }}))
        })
    }));
    result_string(env, match result {
        Ok(Ok(value)) => value, Ok(Err(code)) => fault(code),
        Err(_) => fault("panic"),
    })
}

/// Check all immutable revision JSON against the same exact-root index proof.
/// The Android adapter independently rereads the current head after download.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_heisluka_mobakula_NativeBridge_verifyS3RevisionsProbe(
    mut env: JNIEnv, _receiver: JClass,
    token: JString, head: JByteArray, revisions: JObjectArray,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let id = jtext(&mut env, &token)?;
        let root = read_array(&env, &head, 4096)?;
        let count = env.get_array_length(&revisions).map_err(|_| "jni")? as usize;
        if count > MAX_REVISION_PREVIEW { return Err("revision_bounds"); }
        let mut raw = Vec::with_capacity(count);
        let mut total = 0usize;
        for i in 0..count {
            let item = env.get_object_array_element(&revisions, i as i32)
                .map_err(|_| "jni")?;
            let bytes = read_array(&env, &JByteArray::from(item), MAX_REVISION_JSON_BYTES)?;
            total = total.checked_add(bytes.len()).ok_or("revision_bounds")?;
            if total > MAX_REVISION_PREVIEW * MAX_REVISION_JSON_BYTES {
                return Err("revision_bounds");
            }
            raw.push(bytes);
        }
        with_session(&id, |slot| {
            // A failed recheck must revoke any previously approved blob set.
            slot.blob_head_sha256 = None;
            slot.blob_expectations.clear();
            slot.receive_records.clear();
            slot.proven_blobs_head_sha256 = None;
            slot.provisional_candidates.clear();
            let digest = ring::digest::digest(&ring::digest::SHA256, &root);
            if slot.revision_index_head_sha256.as_ref().map(|x| x.as_slice())
                != Some(digest.as_ref())
            {
                return Err("unverified_revision_root");
            }
            let slices: Vec<&[u8]> = raw.iter().map(Vec::as_slice).collect();
            let preview = inspect_untrusted_revision_records(&slot.revision_refs, &slices)
                .map_err(|_| "invalid_revision_metadata")?;
            let selection = collect_bounded_blob_expectations(&slot.revision_refs, &slices);
            let can_read_blobs = !matches!(&selection, Err(S3BlobProofError::PreviewLimit));
            let candidates = match selection {
                Ok(items) => items,
                Err(S3BlobProofError::PreviewLimit) => Vec::new(),
                Err(_) => return Err("invalid_blob_references"),
            };
            let blob_refs: Vec<Value> = candidates.iter()
                .map(|item| json!({"hash":item.blake3,"bytes":item.bytes}))
                .collect();
            if can_read_blobs {
                slot.receive_records = slices.iter().map(|raw|
                    serde_json::from_slice(raw).map_err(|_| "invalid_revision_records")
                ).collect::<Result<Vec<RevisionRecord>, _>>()?;
                let mut identity = [0u8;32];
                identity.copy_from_slice(digest.as_ref());
                slot.blob_head_sha256 = Some(identity);
                slot.blob_expectations = candidates;
            }
            Ok(json!({"ok":true,"data":{
                "revisionsChecked":preview.revisions_checked,
                "contentRevisions":preview.content_revisions,
                "renameRevisions":preview.rename_revisions,
                "tombstones":preview.tombstones,
                "metadataChecked":true,
                "blobPreviewSupported":can_read_blobs,
                "blobRefs":blob_refs,
                "remoteOwnerVerified":false,"synced":false
            }}))
        })
    }));
    result_string(env, match result {
        Ok(Ok(value)) => value, Ok(Err(code)) => fault(code),
        Err(_) => fault("panic"),
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_heisluka_mobakula_NativeBridge_endS3ReadProbe(
    mut env: JNIEnv, _receiver: JClass, token: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let id = jtext(&mut env, &token)?;
        let mut guard = session_slot().lock().map_err(|_| "state")?;
        if guard.as_ref().is_some_and(|s| s.id == id) { *guard = None; }
        Ok::<Value, &'static str>(json!({"ok":true,"closed":true}))
    }));
    result_string(env, match result {
        Ok(Ok(value)) => value, Ok(Err(code)) => fault(code),
        Err(_) => fault("panic"),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn unknown_session_is_rejected_and_random_ids_change() {
        assert!(matches!(with_session("not-real", |_| Ok(())), Err("no_session")));
        let first = new_id().unwrap();
        let second = new_id().unwrap();
        assert_ne!(first, second);
        assert_eq!(first.len(), 32);
        assert!(first.bytes().all(|b| b.is_ascii_hexdigit()));
    }
}
