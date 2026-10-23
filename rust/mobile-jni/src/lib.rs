//! Versioned Rust/JNI command boundary. No Kotlin writer or raw path operation.
use jni::JNIEnv;
use jni::objects::{JObject, JString, JByteArray, JCharArray};
use mobakula_mobile_api::s3_invite::inspect_untrusted_s3_invite;
use zeroize::Zeroizing;
use jni::sys::jstring;
use mobakula_mobile_api::notes::{NoteVault, NotesError};
use serde::Deserialize;
use serde_json::{Value, json};
use std::ffi::c_void;
use std::path::Path;
use std::ptr;
use std::sync::{Mutex, OnceLock};

mod s3_read_session;

static VAULT: OnceLock<Mutex<Option<NoteVault>>> = OnceLock::new();

#[derive(Deserialize)]
#[serde(tag = "action", rename_all = "snake_case")]
enum Request {
    Init { root: String },
    List,
    Create { content: String },
    Read { id: String },
    Save { id: String, expected_hash: String, content: String },
}

fn error(code: &'static str, message: impl Into<String>) -> NotesError {
    NotesError { code, message: message.into(), note_id: None }
}

fn route(request: Request) -> Result<Value, NotesError> {
    let mut slot = VAULT.get_or_init(|| Mutex::new(None))
        .lock().map_err(|_| error("state", "Note vault lock poisoned"))?;
    match request {
        Request::Init { root } => {
            if let Some(existing) = slot.as_ref() {
                let path = Path::new(&root).canonicalize()
                    .map_err(|cause| error("invalid_root", cause.to_string()))?;
                if existing.base != path {
                    return Err(error("invalid_root", "Vault already open at another location"));
                }
            } else {
                *slot = Some(NoteVault::open(Path::new(&root))?);
            }
            Ok(json!({ "ready": true }))
        }
        other => {
            let vault = slot.as_ref()
                .ok_or_else(|| error("not_ready", "Initialize the app vault first"))?;
            let data = match other {
                Request::List => serde_json::to_value(vault.list()?)
                    .map_err(|cause| error("serialize", cause.to_string()))?,
                Request::Create { content } => serde_json::to_value(vault.create(&content)?)
                    .map_err(|cause| error("serialize", cause.to_string()))?,
                Request::Read { id } => serde_json::to_value(vault.read(&id)?)
                    .map_err(|cause| error("serialize", cause.to_string()))?,
                Request::Save { id, expected_hash, content } =>
                    serde_json::to_value(vault.save(&id, &expected_hash, &content)?)
                        .map_err(|cause| error("serialize", cause.to_string()))?,
                Request::Init { .. } => unreachable!(),
            };
            Ok(data)
        }
    }
}

fn handle(raw: &str) -> String {
    if raw.len() > 1024 * 1024 + 16384 {
        return json!({"ok":false,"code":"too_large","message":"Request exceeds limit"}).to_string();
    }
    let request: Request = match serde_json::from_str(raw) {
        Ok(request) => request,
        Err(cause) => return json!({
            "ok": false, "code": "invalid_request", "message": cause.to_string()
        }).to_string(),
    };
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| route(request)));
    match result {
        Ok(Ok(data)) => json!({ "ok": true, "data": data }).to_string(),
        Ok(Err(cause)) =>
            json!({ "ok": false, "code": cause.code, "message": cause.message,
                "note_id": cause.note_id }).to_string(),
        Err(_) =>
            json!({ "ok": false, "code": "panic", "message": "Native operation failed; restart app" })
                .to_string(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_heisluka_mobakula_NativeBridge_request(
    mut env: JNIEnv, _receiver: JObject, payload: JString,
) -> jstring {
    let response = match env.get_string(&payload) {
        Ok(source) => handle(&source.to_string_lossy()),
        Err(_) => json!({
            "ok": false, "code": "jni", "message": "Invalid Java input"
        }).to_string(),
    };
    env.new_string(response).map_or(ptr::null_mut(), |value| value.into_raw())
}

/// Offline-only: encrypted byte[] and mutable char[] (never JSON credentials).
fn invite_preview_response(encrypted: &[u8], password: &str) -> Value {
    use mobakula_mobile_api::s3_invite::PairInviteError;
    match inspect_untrusted_s3_invite(encrypted, password) {
        Ok(p) => json!({"ok":true,"data":{
            "provider":p.provider,"endpoint":p.endpoint,"region":p.region,
            "bucket":p.bucket,"prefix":p.prefix,"cloudVaultId":p.cloud_vault_id,
            "claimedGenesisFingerprint":p.claimed_genesis_fingerprint,
            "trusted":false
        }}),
        Err(reason) => {
            let code = match reason {
                PairInviteError::TooLarge => "too_large",
                PairInviteError::UnsupportedVersion => "unsupported_version",
                PairInviteError::WeakPassword => "weak_password",
                PairInviteError::InvalidScope => "invalid_scope",
                _ => "invalid_invitation",
            };
            json!({"ok":false,"code":code,
                "message":"Invitation authentication or validation failed"})
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_heisluka_mobakula_NativeBridge_inspectS3Invitation(
    mut env: JNIEnv, _receiver: JObject,
    ciphertext: JByteArray, password: JCharArray,
) -> jstring {
    let response = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let length = env.get_array_length(&ciphertext)
            .map_err(|_| "jni")? as usize;
        let pass_len = env.get_array_length(&password)
            .map_err(|_| "jni")? as usize;
        if length > 16 * 1024 || pass_len > 1024 { return Err("too_large"); }
        let mut signed = vec![0i8; length];
        env.get_byte_array_region(&ciphertext, 0, &mut signed)
            .map_err(|_| "jni")?;
        let encrypted: Vec<u8> = signed.iter().map(|v| *v as u8).collect();
        let mut utf16 = Zeroizing::new(vec![0u16; pass_len]);
        env.get_char_array_region(&password, 0, &mut utf16)
            .map_err(|_| "jni")?;
        let pass = Zeroizing::new(String::from_utf16(&utf16)
            .map_err(|_| "invalid_utf16")?);
        Ok::<_, &'static str>(invite_preview_response(&encrypted, &pass))
    }));
    let data = match response {
        Ok(Ok(value)) => value,
        Ok(Err(code)) => json!({"ok":false,"code":code,
            "message":"Invalid or oversized invitation input"}),
        Err(_) => json!({"ok":false,"code":"panic",
            "message":"Invite validation failed; no state was changed"}),
    };
    env.new_string(data.to_string())
        .map_or(ptr::null_mut(), |v| v.into_raw())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_heisluka_mobakula_NativeBridge_apiVersion(
    _env: *mut c_void, _receiver: *mut c_void,
) -> i32 {
    mobakula_mobile_api::MOBILE_API_VERSION
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn version_is_stable() {
        assert_eq!(mobakula_mobile_api::MOBILE_API_VERSION, 1);
    }

    #[test]
    fn bad_command_is_structured_error() {
        let answer: Value = serde_json::from_str(&handle(r#"{"action":"not_supported"}"#)).unwrap();
        assert_eq!(answer["ok"], false);
        assert_eq!(answer["code"], "invalid_request");
    }

    #[test]
    fn unsafe_invitation_reports_no_password_or_keys() {
        let pass = "temporary-password-more-than-20-characters";
        let body = invite_preview_response(b"wrong-version", pass);
        assert_eq!(body["ok"], false);
        assert_eq!(body["code"], "invalid_invitation");
        assert!(!body.to_string().contains(pass));
    }

    #[test]
    fn request_needs_initialization() {
        let answer: Value = serde_json::from_str(&handle(r#"{"action":"list"}"#)).unwrap();
        assert_eq!(answer["ok"], false);
        assert_eq!(answer["code"], "not_ready");
    }
}
