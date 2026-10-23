# Android S4c — bounded read-only S3 blob proof

Stack: #35 verified revision metadata -> #36 signed immutable revision reads
-> **S4c content-blob verification**. All previous genesis, index and
HTTPS checks remain mandatory in the same short-lived native session.

## Actual read-only behavior

1. JNI validates the complete revision DAG from the exact verified S3 root.
   It derives unique BLAKE3 blob hashes and declared byte lengths from
   content/rename operations only. Tombstones do not authorize a blob.
2. This immutable allowlist is installed only after metadata proof succeeds.
   JNI refuses signing for any hash not on the allowlist. The Rust signer
   constructs only the typed Core key `blobs/<64 lowercase hex>` under the
   invitation namespace, only with HEAD/GET.
3. Android uses the existing TLS-only Yandex host, no redirect and bounded
   HEAD -> signed If-Match GET -> HEAD for every selected blob, including
   exactly empty blobs when the revision explicitly expects zero bytes.
4. Android re-reads the complete `refs/current.json` and compares both
   byte content and ETag after all selected blobs, then passes only raw bytes
   to the JNI BLAKE3 verifier. No blob is parsed as note text or saved.
5. JNI returns only the count of checked immutable blobs and byte totals,
   never the content itself, a local path, sync authority or remote owner
   identity. Android clears transient ByteArrays on completion or failure.

## Deliberate beta limits

At most 16 distinct content blobs, 1 MiB each, 8 MiB combined per probe.
All 64-revision metadata proof limits and 16-shard/128-ancestor index caps
remain. A larger legitimate selection produces an explicit **metadata-only**
result with `contentPreviewSupported=false`, never a false empty or
partially verified content snapshot. The bounded signer session allows 768
HEAD/GET signatures, TTL 180 seconds. A quota/timeout fails closed.

## Security and release gates

No local File Gate import, remote write/delete/LIST, automatic sync, durable
checkpoint, remote account owner proof, or rollback-resistant anchor is
introduced. A matching genesis is not owner authorization. Keep Draft.

Acceptance: portable Rust tests, Android JNI for ARM64/x86_64,
Kotlin tests and APK; independent desktop-produced nonempty MinIO fixture,
real Android + Yandex bucket HEAD/GET, empty-file and binary payload,
404/403/412, malformed ETag, swapped/truncated/corrupted blob, root race,
process interruption and expired session. The CI compile gate does NOT replace
those device/runtime proofs.
