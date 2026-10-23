# S3a: offline immutable ancestry verification

Rust Core V1 S3HeadPointerV1 and S3ManifestV1 are reused without forked serializers.
Given an externally fetched bounded head and complete newest-to-genesis
manifest chain, the mobile API checks canonical bytes, scoped vault identity,
BLAKE3 linkage, monotonic generations and the desktop generation-one pointer
fingerprint. Any missing, extra, foreign or forged ancestor fails closed.

The result is intentionally named UntrustedS3ChainPreview. It is NOT a grant
to use, modify or import S3 data. The network caller must independently
validate the owner-authorized invitation, provider identity, HEAD/If-Match
GET/HEAD stability, post-chain root recheck and rollback-resistant local pin.

Bounds: <=128 ancestors, <=512 KiB each, <=8 MiB cumulative. These tests
use Core V1 constructors; an independent desktop-produced MinIO fixture and
real Android S3 read-only transport remain a separate acceptance requirement.
No INTERNET permission or File Gate change is introduced by this PR.
