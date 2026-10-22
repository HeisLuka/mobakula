//! Portable, versioned entrypoint for future Akula Core integration.
//! M0 exports no note, file, identity, or sync authority.
pub const MOBILE_API_VERSION: i32 = 1;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn version_is_stable_and_nonzero() {
        assert_eq!(MOBILE_API_VERSION, 1);
    }
}

/// Compile-time proof that the upstream Akula File Gate types are present.
/// No mobile mutation is exposed until a real guarded Core wrapper exists.
pub fn file_gate_contract() -> &'static str {
    let _ = std::mem::size_of::<aquilum_core::Core>();
    "aquilum-core-file-gate"
}

pub mod notes;
