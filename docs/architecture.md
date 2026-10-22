# Mobile Akula architecture

Product: offline-first native Android client. Kotlin/Compose owns UI and Android platform lifecycle. Rust owns portable document, file, revision and synchronization semantics. JNI is an intentionally narrow adapter; it does not bypass Akula File Gate.

Planned modules (not yet implemented):
- `android/` — Gradle project, Compose screens, Android lifecycle, file picker and background work.
- `rust/mobile-api/` — small versioned Rust mobile surface.
- `rust/mobile-jni/` — JNI conversion and lifetime management.

The upstream `Lalalalendia/Akula` revision must be pinned. Never copy core files ad hoc or assume the whole desktop crate is Android-compatible. Preserve AGPL-3.0 obligations for integrated Akula code.

Release is distinct from PR CI: physical phone verification, reproducible signed artifact and manual approval precede public distribution.
