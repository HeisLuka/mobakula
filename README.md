# Mobakula

Android client for **Akula**, developed as a separate repository.

## Architecture

- **Kotlin + Jetpack Compose:** Android UI and platform integration.
- **Rust:** portable Akula logic through a narrow JNI boundary.
- **Akula Core:** upstream is [Lalalalendia/Akula](https://github.com/Lalalalendia/Akula). Core integration must use a pinned revision and preserve applicable license notices.

## Development status

Repository bootstrap in progress. No Android APK is claimed or published.

Changes are proposed through pull requests. Project limits and required checks are defined in `CONTRIBUTING.md` and `.github/workflows/ci.yml` once the first policy PR lands.

## Security

No tokens, signing keys, user data, APKs or Gradle/Rust build directories in source control.
