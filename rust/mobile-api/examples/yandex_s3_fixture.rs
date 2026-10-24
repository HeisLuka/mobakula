//! Disposable, nonempty Akula Core V1 graph for an isolated Yandex test bucket.
//! This is a test publisher, NOT a production synchronization writer.
//! S3 upload is a separate, explicitly invoked operation with no overwrite.
use aquilum_core::sync::{
    s3_pair_invite::{open_s3_pair_invite, seal_s3_pair_invite, S3PairInvite},
    DocumentId, OperationId, RevisionId, RevisionOperation, RevisionRecord,
    S3HeadPointerV1, S3Keyspace, S3ManifestShardV1, S3ManifestV1,
    S3RevisionRefV1, SyncRelativePath, VaultId,
};
use ring::digest::{digest, SHA256};
use serde_json::json;
use std::{
    env, fs,
    fs::OpenOptions,
    io::Write,
    path::{Path, PathBuf},
};
use zeroize::Zeroizing;

fn required(name: &str) -> Result<String, String> {
    env::var(name).map_err(|_| format!("required environment variable missing: {name}"))
}

fn sha256_hex(bytes: &[u8]) -> String {
    digest(&SHA256, bytes).as_ref().iter()
        .map(|byte| format!("{byte:02x}")).collect()
}

fn write_new(root: &Path, relative: &Path, body: &[u8]) -> Result<(), String> {
    if relative.is_absolute() || relative.components().any(|part|
        !matches!(part, std::path::Component::Normal(_))
    ) {
        return Err("noncanonical fixture pathname".into());
    }
    let path = root.join(relative);
    fs::create_dir_all(path.parent().ok_or("missing fixture parent")?)
        .map_err(|_| "cannot create fixture directory")?;
    let mut file = OpenOptions::new().write(true).create_new(true).open(&path)
        .map_err(|_| "fixture path already exists or cannot be written")?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        file.set_permissions(fs::Permissions::from_mode(0o600))
            .map_err(|_| "cannot protect fixture file")?;
    }
    file.write_all(body).map_err(|_| "incomplete fixture file write")?;
    file.sync_all().map_err(|_| "fixture file sync failed")?;
    Ok(())
}

fn run() -> Result<(), String> {
    let bucket = required("AKULA_YANDEX_TEST_BUCKET")?;
    if bucket.len() > 63 || !bucket.starts_with("akula-e2e-")
        || !bucket.bytes().all(|b| b.is_ascii_lowercase()
            || b.is_ascii_digit() || b == b'-')
    {
        return Err("bucket must be a dedicated akula-e2e-* test bucket".into());
    }
    let prefix = env::var("AKULA_YANDEX_TEST_PREFIX")
        .unwrap_or_else(|_| "akula-e2e/manual".into());
    if !prefix.starts_with("akula-e2e/") {
        return Err("only the akula-e2e/ prefix is permitted".into());
    }
    let output = PathBuf::from(required("AKULA_YANDEX_FIXTURE_OUTPUT")?);
    if !output.is_absolute() || output.exists() {
        return Err("output must be a fresh, absolute directory".into());
    }
    let access_key_id = required("AKULA_YANDEX_TEST_ACCESS_KEY_ID")?;
    let secret = Zeroizing::new(required("AKULA_YANDEX_TEST_SECRET_ACCESS_KEY")?);
    let password = Zeroizing::new(required("AKULA_YANDEX_INVITE_PASSWORD")?);
    let vault_id = VaultId::new();
    let ns = S3Keyspace::new(&prefix, vault_id)
        .map_err(|_| "invalid Core V1 namespace")?;
    let document_id = DocumentId::new();
    let revision_id = RevisionId::new();
    let note = format!(
        "# Akula S3 acceptance fixture\n\nVault: {vault_id}\n\
         This is disposable Markdown. Do not import personal files.\n"
    ).into_bytes();
    let blob_hash = blake3::hash(&note).to_hex().to_string();
    let revision = RevisionRecord {
        id: revision_id, document_id, parents: vec![],
        operation: RevisionOperation::Content {
            path: SyncRelativePath::parse("received.md")
                .map_err(|_| "invalid Markdown relative path")?,
            blob_hash: blob_hash.clone(),
        },
    };
    let revision_bytes = serde_json::to_vec(&revision)
        .map_err(|_| "revision serialization failed")?;
    let shard = S3ManifestShardV1::new(&ns, OperationId::new(), vec![
        S3RevisionRefV1 {
            revision_id, document_id,
            revision_blake3: blake3::hash(&revision_bytes).to_hex().to_string(),
            blob_bytes: Some(note.len() as u64),
        },
    ]).map_err(|_| "cannot construct V1 shard")?;
    let shard_bytes = shard.encode(&ns)
        .map_err(|_| "cannot serialize V1 shard")?;
    let manifest = S3ManifestV1::new(
        &ns, OperationId::new(), 1, None,
        vec![shard.reference(&ns).map_err(|_| "invalid shard reference")?],
    ).map_err(|_| "cannot construct V1 manifest")?;
    let manifest_bytes = manifest.encode(&ns)
        .map_err(|_| "cannot serialize V1 manifest")?;
    let head = S3HeadPointerV1::new(
        &ns, 1, manifest.manifest_id,
        &manifest.digest(&ns).map_err(|_| "cannot hash V1 manifest")?, None,
    ).map_err(|_| "cannot construct V1 head")?
        .encode(&ns).map_err(|_| "cannot serialize V1 head")?;
    let genesis = blake3::hash(&head).to_hex().to_string();
    let invite = S3PairInvite {
        provider: "yandex".into(),
        endpoint: Some("https://storage.yandexcloud.net".into()),
        region: "ru-central1".into(),
        bucket: bucket.clone(), prefix: prefix.clone(),
        cloud_vault_id: vault_id, genesis_fingerprint: genesis.clone(),
        access_key_id, secret_access_key: secret.to_string(),
        key_expires_at_ms: 0,
    };
    let encrypted = seal_s3_pair_invite(&invite, &password)
        .map_err(|_| "cannot seal S3 test invitation")?;
    let opened = open_s3_pair_invite(&encrypted, &password)
        .map_err(|_| "test invitation failed authentication")?;
    if opened.cloud_vault_id != vault_id || opened.genesis_fingerprint != genesis {
        return Err("test invitation has mismatched identity".into());
    }
    drop(opened);

    // The public list NEVER includes credentials, passwords or plaintext invite.
    let objects = [
        (ns.immutable_blob_key(&blob_hash)
            .map_err(|_| "invalid blob hash")?, note),
        (ns.immutable_revision_key(revision_id), revision_bytes),
        (ns.immutable_manifest_key(shard.shard_id), shard_bytes),
        (ns.immutable_manifest_key(manifest.manifest_id), manifest_bytes),
        (ns.current_head_key(), head),
    ];
    fs::create_dir(&output).map_err(|_| "cannot create exclusive test directory")?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(&output, fs::Permissions::from_mode(0o700))
            .map_err(|_| "cannot secure test directory")?;
    }
    let mut inventory = Vec::new();
    for (key, body) in objects {
        write_new(&output, Path::new("objects").join(&key).as_path(), &body)?;
        inventory.push(json!({
            "key": key, "sha256": sha256_hex(&body), "bytes": body.len()
        }));
    }
    write_new(&output, Path::new("invite.akula-sync"), &encrypted)?;
    let metadata = json!({
        "schema": 1, "provider": "yandex", "bucket": bucket, "prefix": prefix,
        "vaultId": vault_id.to_string(), "documentId": document_id.to_string(),
        "revisionId": revision_id.to_string(), "genesisFingerprint": genesis,
        "objects": inventory,
        "remoteOwnerVerified": false, "syncAuthorized": false
    });
    write_new(&output, Path::new("index.json"),
        &serde_json::to_vec_pretty(&metadata)
            .map_err(|_| "metadata serialization failed")?)?;
    println!("Disposable Akula Core V1 fixture written; 5 objects, root last.");
    println!("No remote writes performed. Keep invite.akula-sync private.");
    Ok(())
}

fn main() {
    if let Err(error) = run() {
        eprintln!("S3 fixture rejected: {error}");
        std::process::exit(1);
    }
}
