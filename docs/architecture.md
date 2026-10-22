# Mobile Akula architecture

## M0 Android application

`android/` is a standalone Gradle project with Kotlin, Jetpack Compose, and Android Material 3. It contains three clearly marked sections: local notes, device pairing, and server setup. Only navigation exists today: no data-store, network calls, server authority, or JNI bridge.

The Android manifest intentionally declares **no internet or storage permissions** in this phase. It does not claim local storage or pairing works. The debug APK is compiled in the existing single `mobile-gate` workflow, never uploaded into Git.

## Planned boundaries

- Android UI owns screen state, Android platform lifecycle, file picker and background work.
- `rust/mobile-api/` is a future small versioned portable Rust API.
- `rust/mobile-jni/` is a future JNI bridge; it must not write to the vault around File Gate.
- Core data/history/synchronization semantics remain in pinned Akula Core, not rewritten in Kotlin.

Upstream: [Lalalalendia/Akula](https://github.com/Lalalalendia/Akula). Preserve AGPL-3.0 obligations for integrated code. Before JNI integration, prove the selected Core module compiles for Android and specify error, cancellation and memory ownership semantics.

Public releases require separate signing, physical device acceptance and explicit approval. M0 only establishes an installable debug application.
