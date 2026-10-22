//! Versioned Rust/JNI command boundary. No Kotlin writer or raw path operation.
use jni::JNIEnv;
use jni::objects::{JObject, JString};
use jni::sys::jstring;
use mobakula_mobile_api::notes::{NoteVault, NotesError};
use serde::Deserialize;
use serde_json::{Value, json};
use std::ffi::c_void;
use std::path::Path;
use std::ptr;
use std::sync::{Mutex, OnceLock};

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
    NotesError { code, message: message.into() }
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
            json!({ "ok": false, "code": cause.code, "message": cause.message }).to_string(),
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
    fn request_needs_initialization() {
        let answer: Value = serde_json::from_str(&handle(r#"{"action":"list"}"#)).unwrap();
        assert_eq!(answer["ok"], false);
        assert_eq!(answer["code"], "not_ready");
    }
}
