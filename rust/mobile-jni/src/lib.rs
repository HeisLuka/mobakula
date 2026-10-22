//! M0: a JNI symbol returning the portable Rust API version.
//! JNI pointers are opaque and not dereferenced. Do not expand this API
//! without typed JNI bindings, error handling and memory ownership rules.
use std::ffi::c_void;

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_heisluka_mobakula_NativeBridge_apiVersion(
    _env: *mut c_void,
    _receiver: *mut c_void,
) -> i32 {
    mobakula_mobile_api::MOBILE_API_VERSION
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn jni_version_matches_portable_api() {
        assert_eq!(
            Java_dev_heisluka_mobakula_NativeBridge_apiVersion(
                std::ptr::null_mut(),
                std::ptr::null_mut(),
            ),
            1,
        );
    }
}
