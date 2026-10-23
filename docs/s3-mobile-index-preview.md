# Android S3d — immutable index read-only preview

After the S3c network probe has:
1. fetched the current pointer with HEAD/If-Match GET/HEAD,
2. walked and checked complete immutable ancestry against the invited genesis,
3. reread the exact pointer and ETag,

the S3d code parses the newest manifest's shard references. It fetches each
shard under the same typed manifests/<UUID>.json namespace, with the same
bounded HEAD/If-Match GET/HEAD pattern, and rechecks the root a **third time**
after all shards. No ListObjectsV2 or raw arbitrary keys are accepted.

The new Rust Core-backed s3_index module checks each shard's canonical V1
bytes, scoped VaultId, BLAKE3 digest, revision count and first/last revision.
The native JNI endpoint refuses index verification without a successful
ancestry proof **within the same short-lived signer session** for the exact
head bytes (SHA-256 session marker). No keys are returned to Kotlin.

Temporary beta preview limits:
- 16 shards maximum; 128 KiB per shard; <=2 MiB cumulative shard bytes;
- 128 ancestors and 8 MiB ancestry limit retained from S3a;
- 640 signatures and 180-second signer session retained from S3b.
If a real remote index exceeds the preview budget, return a visible
validation failure rather than showing incomplete or zero documents.

The read-only UI reports verified index counts for *distinct document IDs*,
revisions and shard objects. This is NOT proof of a usable file, safe document
content, provider-account ownership, remote rollback resistance, receive receipt,
or write access. No notes, histories, baselines or local files are changed.

This draft does not permit S4 import or S5 upload. An independent
desktop-produced MinIO fixture and a real Android Yandex bucket integration
are still needed before declaring the transport accepted.

**V1 provenance boundary:** canonical head/manifest/shard JSON includes VaultId,
not the configured prefix. A different *valid* prefix cannot be rejected from
these bytes alone. The native signer fixes the invitation prefix and signs
exact typed S3 paths; the Kotlin transport fetches only those signed paths.
Invalid/traversal prefixes still fail in S3Keyspace::new.
