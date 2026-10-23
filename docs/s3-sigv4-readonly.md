# Android S3: read-only SigV4 native signer

Scoped to a password-authenticated Yandex S3 invitation, fixed HTTPS
storage.yandexcloud.net, region ru-central1, path-style bucket, and
Core-generated only refs/current.json or one immutable manifest key.

HEAD/GET only. No endpoint override, LIST/PUT/POST/DELETE, dynamic path,
redirect, ambient credential provider, File Gate, trusted genesis or sync
status. Reuses Akula Core V1 invite/namespace; implements AWS SigV4 SHA256
with ring HMAC for the read-only HTTP adapter.

The signer remains in native memory while a user-triggered read-only probe
runs. Signatures contain access-key identification and are short-lived
bearer-like values: NEVER log, save or display Authorization headers.
A later JNI session must impose a hard TTL/request count and wipe secrets
on cancellation. Kotlin HTTP transport MUST reject redirects, use HTTPS,
validate bounded Content-Length, HEAD/If-Match GET/HEAD and recheck the
same immutable current-head bytes/ETag after all manifests are fetched.
Full chain validation uses upstream Core in the preceding stacked S3a PR.
A green signer unit test alone cannot claim working S3 access.
