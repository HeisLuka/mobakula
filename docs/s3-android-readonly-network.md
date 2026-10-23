# S3b read-only Android transport: bounded signed HEAD/GET

User action: after importing a password-authenticated .akula-sync profile
into Android Keystore, press "Проверить S3 (только чтение)". The app
unseals the profile only on a worker and begins a short native signer
session (180 seconds, <=640 signatures). Credentials remain inside Rust.
The Kotlin adapter receives short-lived signed HTTP requests and opens
ONLY https://storage.yandexcloud.net, with TLS, redirects off, a 5s connect
and 10s read timeout; it does not log URLs or Authorization headers.

For each root/immutable manifest: HEAD -> signed If-Match GET -> HEAD,
require identical quoted ETags, exact known Content-Length, no compression
and bounded bytes. Max 4 KiB root, 512 KiB manifest, 128 ancestors, 8 MiB
total. After reading all ancestors it re-reads and compares the entire
current root (including ETag) before calling the shared Core V1 verifier
from S3a. The network signer enforces only refs/current.json and typed
manifests/<UUID>.json. No LIST, PUT, delete, or note File Gate operation.

A successful response is a read-only integrity observation of data whose
claimed genesis matches an authenticated invitation. It does NOT prove
that the S3 provider's account is owned by the user, prevent rollback
before any independent durable checkpoint, or authorize note mutation.
The UI never displays "Connected" or "Synced". Mobile Yandex is the only
provider allowed in this version; other S3-compatible endpoints are out
of scope until separate validation.

This uses the standard Android HTTPS stack as a network adapter, not a
parallel Sync Core. Manifest/genesis rules and invitation decryption
remain Rust and share the desktop Core. INTERNET permission is present
in this isolated Draft branch only; normal main and installed APK
remain unchanged. A disposable test Yandex/MinIO account and on-device
verification are release gates, not claimed by Rust compilation.
