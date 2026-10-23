//! Short-lived SigV4 signer for *read only* Yandex S3 objects.
//! The normal JSON notes API never receives a password or secret key.
//! This is not a general S3 client or a network/genesis trust grant.
//! Only a caller-provided Android HTTP adapter will execute these requests.

use std::str::FromStr;
use aquilum_core::sync::{OperationId, RevisionId, S3Keyspace};
use aquilum_core::sync::s3_pair_invite::{
    open_s3_pair_invite, S3PairInvite,
};
use ring::{digest, hmac};
use zeroize::Zeroize;

const HOST: &str = "storage.yandexcloud.net";
const REGION: &str = "ru-central1";
pub const EMPTY_SHA256: &str =
    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum S3ReadSignerError {
    InvalidInvite, UnsupportedProvider, InvalidMethod, InvalidObject,
    InvalidDate, InvalidEtag,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum S3ReadObject {
    CurrentHead,
    ImmutableManifest(OperationId),
    ImmutableRevision(RevisionId),
}

/// Do not implement Debug/Serialize: a signed Authorization header is
/// replayable for a short time and must never be logged or saved.
pub struct S3SignedRead {
    pub url: String,
    pub method: &'static str,
    pub authorization: String,
    pub amz_date: String,
    pub if_match: Option<String>,
}

impl Drop for S3SignedRead {
    fn drop(&mut self) {
        self.authorization.zeroize();
    }
}

/// A session owner must keep this value in native process memory for only a
/// bounded user-triggered probe, and drop it on completion/cancellation.
pub struct S3ReadSigner {
    invite: S3PairInvite,
    namespace: S3Keyspace,
}

impl S3ReadSigner {
    pub fn from_encrypted_invitation(
        envelope: &[u8], password: &str,
    ) -> Result<Self, S3ReadSignerError> {
        let invite = open_s3_pair_invite(envelope, password)
            .map_err(|_| S3ReadSignerError::InvalidInvite)?;
        Self::from_authenticated_invitation(invite)
    }

    fn from_authenticated_invitation(
        invite: S3PairInvite,
    ) -> Result<Self, S3ReadSignerError> {
        if invite.provider != "yandex"
            || invite.endpoint.as_deref() != Some("https://storage.yandexcloud.net")
            || invite.region != REGION
        {
            return Err(S3ReadSignerError::UnsupportedProvider);
        }
        invite.validate().map_err(|_| S3ReadSignerError::InvalidInvite)?;
        let namespace = S3Keyspace::new(&invite.prefix, invite.cloud_vault_id)
            .map_err(|_| S3ReadSignerError::InvalidInvite)?;
        Ok(Self { invite, namespace })
    }

    pub fn cloud_vault_id(&self) -> String {
        self.invite.cloud_vault_id.to_string()
    }

    pub fn genesis_fingerprint(&self) -> &str {
        &self.invite.genesis_fingerprint
    }

    pub fn prefix(&self) -> &str {
        &self.invite.prefix
    }

    pub fn current_head_key(&self) -> String {
        self.namespace.current_head_key()
    }

    pub fn sign(
        &self, object: S3ReadObject, method: &str, utc_stamp: &str,
        if_match: Option<&str>,
    ) -> Result<S3SignedRead, S3ReadSignerError> {
        let verb = match method {
            "HEAD" => "HEAD",
            "GET" => "GET",
            _ => return Err(S3ReadSignerError::InvalidMethod),
        };
        let stamp = utc_stamp.as_bytes();
        if stamp.len() != 16 || stamp[8] != b'T' || stamp[15] != b'Z'
            || stamp.iter().enumerate().any(|(i, byte)| {
                i != 8 && i != 15 && !byte.is_ascii_digit()
            })
        {
            return Err(S3ReadSignerError::InvalidDate);
        }
        if let Some(etag) = if_match {
            if etag.len() < 3 || etag.len() > 130 || !etag.starts_with('"')
                || !etag.ends_with('"')
                || !etag.as_bytes()[1..etag.len() - 1].iter().all(|b| {
                    b.is_ascii_alphanumeric() || matches!(*b, b'-' | b'_' | b'.')
                })
            {
                return Err(S3ReadSignerError::InvalidEtag);
            }
        }
        let key = match object {
            S3ReadObject::CurrentHead => self.namespace.current_head_key(),
            S3ReadObject::ImmutableManifest(id) =>
                self.namespace.immutable_manifest_key(id),
            S3ReadObject::ImmutableRevision(id) =>
                self.namespace.immutable_revision_key(id),
        };
        // The typed Core keyspace can only generate ASCII unreserved plus slash.
        if !key.bytes().all(|b|
            b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.' | b'/')
        ) {
            return Err(S3ReadSignerError::InvalidObject);
        }
        let path = format!("/{}/{}", self.invite.bucket, key);
        let date = &utc_stamp[..8];
        let credential_scope = format!("{date}/{REGION}/s3/aws4_request");
        let signed_headers = if if_match.is_some() {
            "host;if-match;x-amz-content-sha256;x-amz-date"
        } else {
            "host;x-amz-content-sha256;x-amz-date"
        };
        let canonical_headers = if let Some(etag) = if_match {
            format!("host:{HOST}\nif-match:{etag}\nx-amz-content-sha256:{EMPTY_SHA256}\nx-amz-date:{utc_stamp}\n")
        } else {
            format!("host:{HOST}\nx-amz-content-sha256:{EMPTY_SHA256}\nx-amz-date:{utc_stamp}\n")
        };
        let canonical = format!(
            "{verb}\n{path}\n\n{canonical_headers}\n{signed_headers}\n{EMPTY_SHA256}"
        );
        let hash = digest::digest(&digest::SHA256, canonical.as_bytes());
        let to_sign = format!(
            "AWS4-HMAC-SHA256\n{utc_stamp}\n{credential_scope}\n{}",
            hex_lower(hash.as_ref()),
        );
        let mut seed = Vec::with_capacity(4 + self.invite.secret_access_key.len());
        seed.extend_from_slice(b"AWS4");
        seed.extend_from_slice(self.invite.secret_access_key.as_bytes());
        let mut date_key = mac(&seed, date.as_bytes());
        seed.zeroize();
        let mut region_key = mac(&date_key, REGION.as_bytes());
        date_key.zeroize();
        let mut service_key = mac(&region_key, b"s3");
        region_key.zeroize();
        let mut signing_key = mac(&service_key, b"aws4_request");
        service_key.zeroize();
        let mut signature = mac(&signing_key, to_sign.as_bytes());
        signing_key.zeroize();
        let authorization = format!(
            "AWS4-HMAC-SHA256 Credential={}/{},SignedHeaders={},Signature={}",
            self.invite.access_key_id, credential_scope, signed_headers,
            hex_lower(&signature),
        );
        signature.zeroize();
        Ok(S3SignedRead {
            url: format!("https://{HOST}{path}"),
            method: verb,
            authorization,
            amz_date: utc_stamp.to_owned(),
            if_match: if_match.map(str::to_owned),
        })
    }
}

fn mac(key: &[u8], data: &[u8]) -> [u8; 32] {
    let signature = hmac::sign(
        &hmac::Key::new(hmac::HMAC_SHA256, key), data,
    );
    let mut result = [0u8; 32];
    result.copy_from_slice(signature.as_ref());
    result
}

fn hex_lower(data: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut s = String::with_capacity(data.len() * 2);
    for b in data {
        s.push(HEX[(b >> 4) as usize] as char);
        s.push(HEX[(b & 15) as usize] as char);
    }
    s
}

pub fn parse_manifest_id(value: &str) -> Result<S3ReadObject, S3ReadSignerError> {
    // Do not accept noncanonical spelling, path separators, or arbitrary keys.
    let id = OperationId::from_str(value)
        .map_err(|_| S3ReadSignerError::InvalidObject)?;
    if id.to_string() != value {
        return Err(S3ReadSignerError::InvalidObject);
    }
    Ok(S3ReadObject::ImmutableManifest(id))
}

/// Only canonical typed revision UUIDs, never an arbitrary S3 object path.
pub fn parse_revision_id(value: &str) -> Result<RevisionId, S3ReadSignerError> {
    let id = RevisionId::from_str(value)
        .map_err(|_| S3ReadSignerError::InvalidObject)?;
    if id.to_string() != value {
        return Err(S3ReadSignerError::InvalidObject);
    }
    Ok(id)
}

#[cfg(test)]
mod tests {
    use super::*;
    use aquilum_core::sync::{VaultId, s3_pair_invite::seal_s3_pair_invite};

    fn source() -> S3PairInvite {
        S3PairInvite {
            provider: "yandex".to_owned(),
            endpoint: Some("https://storage.yandexcloud.net".to_owned()),
            region: REGION.to_owned(),
            bucket: "akula-demo".to_owned(),
            prefix: "akula-sync/pair".to_owned(),
            cloud_vault_id: VaultId::from_str("11111111-2222-4333-8444-555555555555").unwrap(),
            genesis_fingerprint: "a".repeat(64),
            access_key_id: "test-key-123".to_owned(),
            secret_access_key: "test-secret-key-material-1234".to_owned(),
            key_expires_at_ms: 0,
        }
    }

    #[test]
    fn only_yandex_and_scoped_read_methods_can_be_signed() {
        let signer = S3ReadSigner::from_authenticated_invitation(source()).unwrap();
        let signed = signer.sign(
            S3ReadObject::CurrentHead, "GET", "20261024T120001Z", None
        ).unwrap();
        assert_eq!(signed.method, "GET");
        assert_eq!(signed.url, format!(
            "https://{HOST}/akula-demo/akula-sync/pair/v1/vaults/{}/refs/current.json",
            signer.cloud_vault_id()
        ));
        // Independently derived with Python stdlib hmac/hashlib using the
        // AWS documented canonical-request algorithm, not this Rust signer.
        assert_eq!(signed.authorization, concat!(
            "AWS4-HMAC-SHA256 Credential=test-key-123/20261024/ru-central1/s3/aws4_request,",
            "SignedHeaders=host;x-amz-content-sha256;x-amz-date,",
            "Signature=cd52465c07d8f8aa81958a25507d25d1a4629166d2946b4a8229c1ed4a9e9711"
        ));
        assert!(!signed.authorization.contains("test-secret"));
        assert!(!signed.authorization.contains('\n'));
        assert_eq!(signer.sign(S3ReadObject::CurrentHead, "PUT",
            "20261024T120001Z", None).err(), Some(S3ReadSignerError::InvalidMethod));
    }

    #[test]
    fn immutable_manifest_and_if_match_are_signed_but_injected_header_is_rejected() {
        let signer = S3ReadSigner::from_authenticated_invitation(source()).unwrap();
        let id = parse_manifest_id("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee").unwrap();
        let signed = signer.sign(id, "GET", "20261024T120001Z", Some("\"deadbeef\"")).unwrap();
        assert!(signed.url.ends_with("/manifests/aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee.json"));
        assert!(signed.authorization.contains("SignedHeaders=host;if-match;x-amz-content-sha256;x-amz-date,"));
        assert!(matches!(signer.sign(S3ReadObject::CurrentHead, "GET", "20261024T120001Z",
            Some("\"etag\"\r\nx-unsafe:123")), Err(S3ReadSignerError::InvalidEtag)));
        assert_eq!(parse_manifest_id("../secret"), Err(S3ReadSignerError::InvalidObject));
        assert!(matches!(signer.sign(S3ReadObject::CurrentHead, "GET",
            "20261024T120001Z\n", None), Err(S3ReadSignerError::InvalidDate)));
    }

    #[test]
    fn only_encrypted_password_authenticated_invite_enables_signer() {
        let original = source();
        let envelope = seal_s3_pair_invite(
            &original, "password-for-two-trusted-devices"
        ).unwrap();
        assert!(S3ReadSigner::from_encrypted_invitation(
            &envelope, "not-this-password"
        ).is_err());
        let signer = S3ReadSigner::from_encrypted_invitation(
            &envelope, "password-for-two-trusted-devices"
        ).unwrap();
        assert_eq!(signer.current_head_key(), format!(
            "akula-sync/pair/v1/vaults/{}/refs/current.json",
            original.cloud_vault_id
        ));
        let mut other = source();
        other.provider = "custom".into();
        assert!(matches!(S3ReadSigner::from_authenticated_invitation(other),
            Err(S3ReadSignerError::UnsupportedProvider)));
    }

    #[test]
    fn exact_typed_revision_is_signed_but_outside_keys_fail() {
        let signer = S3ReadSigner::from_authenticated_invitation(source()).unwrap();
        let id = parse_revision_id("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee").unwrap();
        let signed = signer.sign(S3ReadObject::ImmutableRevision(id),
            "GET", "20261024T120001Z", None).unwrap();
        assert!(signed.url.ends_with("/revisions/aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee.json"));
        assert!(parse_revision_id("../other/secret").is_err());
        assert!(parse_revision_id("AAAAAAAA-BBBB-4CCC-8DDD-EEEEEEEEEEEE").is_err());
        assert!(signer.sign(S3ReadObject::ImmutableRevision(id),
            "PUT", "20261024T120001Z", None).is_err());
    }

    #[test]
    fn method_changes_signed_request_and_no_endpoint_escape() {
        let signer = S3ReadSigner::from_authenticated_invitation(source()).unwrap();
        let get = signer.sign(S3ReadObject::CurrentHead, "GET", "20261024T120001Z", None).unwrap();
        let head = signer.sign(S3ReadObject::CurrentHead, "HEAD", "20261024T120001Z", None).unwrap();
        assert_ne!(get.authorization, head.authorization);
        assert!(signer.sign(S3ReadObject::CurrentHead, "GET", "20261024x120001Z", None).is_err());
    }
}
