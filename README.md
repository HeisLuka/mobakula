# Mobakula

The native Android application for [Akula](https://github.com/Lalalalendia/Akula).

## Current milestone: M0 (Android scaffold)

An Android app with Kotlin and Jetpack Compose, three honest navigation sections, and an offline-only launcher. Local note editing, Akula Core integration, pairing and server management are **not implemented** yet; the interface makes this explicit.

### Development

- Android Gradle Plugin **9.1.1**, Gradle **9.3.1**, JDK **17**.
- `compileSdk = 37`, `targetSdk = 36`, `minSdk = 26`.
- Kotlin support is built into AGP 9; Compose compiler plugin **2.2.10**, Compose BOM **2026.09.00**.
- From repository root, after installing Android SDK 37 and Gradle 9.3.1:

```sh
gradle --no-daemon -p android :app:assembleDebug
```

GitHub Actions [Mobile gate](.github/workflows/ci.yml) compiles an installable **debug APK** for Android source/CI changes and uploads it as a short-lived Actions artifact. Debug APK uses only Android's debug signing and is not a production release.

### Architecture and policy

Kotlin+Compose implements Android user interface and lifecycle. Portable logic will be Rust via a narrow JNI boundary; integration with Akula Core requires a pinned revision and license review.

See [contribution and size/CI rules](CONTRIBUTING.md), [architecture](docs/architecture.md), [admin setup](docs/admin-setup.md). No networking permission or access to private files is requested at M0.

## Native bridge M0

A tiny Rust `mobile-api` exports a version constant through a JNI `cdylib`. Android loads this through `NativeBridge.apiVersion()` and reports the result. The Actions job cross-compiles arm64-v8a and x86_64 libraries with the pinned Android NDK 28.2.13676358, bundles them inside the debug APK and verifies both entries. **This is ABI wiring, not Akula Core integration or note persistence.**
