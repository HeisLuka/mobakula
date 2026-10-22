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
