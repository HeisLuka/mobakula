//! S1 groundwork: open the actual desktop .akula-sync V1 envelope offline.
//!
//! No network, Keystore, JNI, File Gate or trust admission. Keys never leave
//! the temporary upstream S3PairInvite object, whose Drop zeroizes them.
//! Do NOT pass plaintext passwords through the existing JSON JNI command.

use aquilum_core::sync::s3_pair_invite::open_s3_pair_invite;
pub use aquilum_core::sync::s3_pair_invite::PairInviteError;
use aquilum_core::sync::s3_pair_profile::S3PairProfile;

/// Authenticated invite metadata, NOT verified against S3 genesis or owner.
/// Intentionally excludes accessKeyId, secretAccessKey, and local paths.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct UntrustedS3PairProfile {
    pub provider: String,
    pub endpoint: Option<String>,
    pub region: String,
    pub bucket: String,
    pub prefix: String,
    pub cloud_vault_id: String,
    pub claimed_genesis_fingerprint: String,
}

pub fn inspect_untrusted_s3_invite(
    encrypted: &[u8], password: &str,
) -> Result<UntrustedS3PairProfile, PairInviteError> {
    let sensitive = open_s3_pair_invite(encrypted, password)?;
    let profile = S3PairProfile::from_authenticated_invite(&sensitive);
    profile.validate()?;
    Ok(UntrustedS3PairProfile {
        provider: profile.provider,
        endpoint: profile.endpoint,
        region: profile.region,
        bucket: profile.bucket,
        prefix: profile.prefix,
        cloud_vault_id: profile.cloud_vault_id.to_string(),
        claimed_genesis_fingerprint: profile.genesis_fingerprint,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use aquilum_core::sync::{VaultId, s3_pair_invite::{S3PairInvite, seal_s3_pair_invite}};

    const PASSWORD: &str = "long password for the two trusted devices";

    fn desktop_invite() -> S3PairInvite {
        S3PairInvite {
            provider: "yandex".into(),
            endpoint: Some("https://storage.yandexcloud.net".into()),
            region: "ru-central1".into(),
            bucket: "akula-mobile-test".into(),
            prefix: "akula-sync/pair".into(),
            cloud_vault_id: VaultId::new(),
            genesis_fingerprint: "a".repeat(64),
            access_key_id: "private-test-id".into(),
            secret_access_key: "private-test-secret-123456789".into(),
            key_expires_at_ms: 0,
        }
    }

    #[test]
    fn desktop_encrypted_invite_opens_without_exposing_keys() {
        let original = desktop_invite();
        let ciphertext = seal_s3_pair_invite(&original, PASSWORD).unwrap();
        let preview = inspect_untrusted_s3_invite(&ciphertext, PASSWORD).unwrap();
        assert_eq!(preview.bucket, original.bucket);
        assert_eq!(preview.cloud_vault_id, original.cloud_vault_id.to_string());
        assert_eq!(preview.claimed_genesis_fingerprint, original.genesis_fingerprint);
        let diagnostic = format!("{preview:?}");
        assert!(!diagnostic.contains(&original.access_key_id));
        assert!(!diagnostic.contains(&original.secret_access_key));
        assert!(!ciphertext.windows(original.secret_access_key.len())
            .any(|w| w == original.secret_access_key.as_bytes()));
    }

    #[test]
    fn wrong_password_ciphertext_and_version_fail_closed() {
        let encrypted = seal_s3_pair_invite(&desktop_invite(), PASSWORD).unwrap();
        assert_eq!(inspect_untrusted_s3_invite(&encrypted, "this is a wrong password"),
            Err(PairInviteError::AuthenticationFailed));
        let mut flipped = encrypted.clone();
        let index = flipped.len() - 1;
        flipped[index] ^= 1;
        assert_eq!(inspect_untrusted_s3_invite(&flipped, PASSWORD),
            Err(PairInviteError::AuthenticationFailed));
        let mut version = encrypted;
        version[0] ^= 1;
        assert_eq!(inspect_untrusted_s3_invite(&version, PASSWORD),
            Err(PairInviteError::UnsupportedVersion));
    }

    #[test]
    fn utf8_password_is_bounded_by_core_bytes_not_android_char_count() {
        let source = desktop_invite();
        let password = "пароль-для-связки";
        assert!(password.chars().count() < 20);
        assert!(password.len() >= 20);
        let encrypted = seal_s3_pair_invite(&source, password).unwrap();
        assert_eq!(inspect_untrusted_s3_invite(&encrypted, password)
            .unwrap().cloud_vault_id, source.cloud_vault_id.to_string());
    }

    #[test]
    fn oversized_or_truncated_invitation_never_yields_preview() {
        let encrypted = seal_s3_pair_invite(&desktop_invite(), PASSWORD).unwrap();
        let mut excessive = encrypted.clone();
        excessive.resize(16 * 1024 + 1, 0);
        assert_eq!(inspect_untrusted_s3_invite(&excessive, PASSWORD),
            Err(PairInviteError::TooLarge));
        assert_eq!(inspect_untrusted_s3_invite(&encrypted[..10], PASSWORD),
            Err(PairInviteError::InvalidInvitation));
        assert_eq!(inspect_untrusted_s3_invite(&encrypted, "short"),
            Err(PairInviteError::WeakPassword));
    }
}
