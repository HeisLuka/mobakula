//! S4c native-only signed S3 blob reads and verification. No file authority.
use jni::{JNIEnv, objects::{JByteArray, JClass, JObjectArray, JString}};
use jni::sys::jstring;
use mobakula_mobile_api::s3_readonly::{EMPTY_SHA256, S3ReadObject};
use mobakula_mobile_api::s3_blobs::{
    verify_bounded_blob_bytes, MAX_BLOB_COUNT, MAX_BLOB_BYTES, MAX_BLOB_TOTAL,
};
use mobakula_mobile_api::s3_receive_plan::preview_untrusted_s3_additive_receive;
use serde_json::json;
use zeroize::Zeroizing;
use crate::s3_read_session::{
    fault, jtext, read_array, result_string, with_session, MAX_READ_SIGNATURES,
};

/// Only exact content digests extracted from a fully verified revision DAG
/// can be signed; no arbitrary S3 key, no remote mutations or local writes.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_heisluka_mobakula_NativeBridge_signS3BlobProbe(
    mut env: JNIEnv, _receiver: JClass,
    session: JString, hash: JString, method: JString,
    if_match: JString, utc_stamp: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let token = jtext(&mut env, &session)?;
        let hash = jtext(&mut env, &hash)?;
        let method = jtext(&mut env, &method)?;
        let if_match = jtext(&mut env, &if_match)?;
        let stamp = jtext(&mut env, &utc_stamp)?;
        with_session(&token, |slot| {
            if slot.signatures >= MAX_READ_SIGNATURES { return Err("quota"); }
            if slot.blob_head_sha256.is_none()
                || !slot.blob_expectations.iter().any(|item| item.blake3 == hash)
            {
                return Err("blob_not_in_verified_revisions");
            }
            let signed = slot.signer.sign(
                S3ReadObject::ImmutableBlob(hash), &method, &stamp,
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

/// Blob bytes never become JSON, application files or sync receipts. Empty
/// blobs are legal only when the immutable revision records expect them.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_heisluka_mobakula_NativeBridge_verifyS3BlobsProbe(
    mut env: JNIEnv, _receiver: JClass,
    token: JString, head: JByteArray, blobs: JObjectArray,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let id = jtext(&mut env, &token)?;
        let head = read_array(&env, &head, 4096)?;
        let count = env.get_array_length(&blobs).map_err(|_| "jni")? as usize;
        if count > MAX_BLOB_COUNT { return Err("blob_bounds"); }
        let mut raw = Vec::<Zeroizing<Vec<u8>>>::with_capacity(count);
        let mut total = 0usize;
        for i in 0..count {
            let item = env.get_object_array_element(&blobs, i as i32)
                .map_err(|_| "jni")?;
            let arr = JByteArray::from(item);
            let len = env.get_array_length(&arr).map_err(|_| "jni")? as usize;
            if len > MAX_BLOB_BYTES { return Err("blob_bounds"); }
            total = total.checked_add(len).ok_or("blob_bounds")?;
            if total > MAX_BLOB_TOTAL { return Err("blob_bounds"); }
            let mut signed = Zeroizing::new(vec![0i8; len]);
            if len != 0 {
                env.get_byte_array_region(&arr, 0, &mut signed)
                    .map_err(|_| "jni")?;
            }
            raw.push(Zeroizing::new(signed.iter().map(|b| *b as u8).collect()));
        }
        with_session(&id, |slot| {
            // An unsuccessful repeated proof revokes the prior local scan.
            slot.proven_blobs_head_sha256 = None;
            slot.provisional_candidates.clear();
            let digest = ring::digest::digest(&ring::digest::SHA256, &head);
            if slot.blob_head_sha256.as_ref().map(|x| x.as_slice())
                != Some(digest.as_ref())
            {
                return Err("unverified_blob_root");
            }
            let slices: Vec<&[u8]> = raw.iter().map(|v| v.as_slice()).collect();
            let proof = verify_bounded_blob_bytes(&slot.blob_expectations, &slices)
                .map_err(|_| "invalid_blob_content")?;
            let plan = preview_untrusted_s3_additive_receive(
                &slot.receive_records, &slot.blob_expectations, &slices,
            ).map_err(|_| "invalid_receive_preflight")?;
            let mut identity = [0u8;32];
            identity.copy_from_slice(digest.as_ref());
            slot.proven_blobs_head_sha256 = Some(identity);
            slot.provisional_candidates = plan.candidates.clone();
            let candidate_refs: Vec<_> = plan.candidates.iter().map(|c| json!({
                "documentId":c.document_id.to_string(),
                "revisionId":c.revision_id.to_string(),
                "blobHash":c.blob_blake3,"bytes":c.bytes,
            })).collect();
            Ok(json!({"ok":true,"data":{
                "blobsChecked":proof.blobs_checked,
                "bytesChecked":proof.bytes_checked,
                "contentChecked":true,
                "receiveCandidates":plan.candidates.len(),
                "candidateRefs":candidate_refs,
                "needsReview":plan.needs_review(),
                "forkedDocuments":plan.forked_documents,
                "deletedDocuments":plan.deleted_documents,
                "unsupportedDocuments":plan.unsupported_documents,
                "localConflictsChecked":false,
                "importAuthorized":false,
                "remoteOwnerVerified":false,"synced":false
            }}))
        })
    }));
    result_string(env, match result {
        Ok(Ok(value)) => value, Ok(Err(code)) => fault(code),
        Err(_) => fault("panic"),
    })
}

