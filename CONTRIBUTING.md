# Mobakula development policy (v1)

## Repository and ownership
- Android UI: Kotlin + Jetpack Compose. Portable logic: Rust. JNI is a narrow boundary.
- Portable Akula code is consumed from a pinned, reviewed revision. Preserve source license and attribution; do not paste unreviewed upstream code.
- One responsibility per module. No business logic in Kotlin/JNI adapters.
- All routine changes use a PR; no direct pushes to main after bootstrap.

## Limits enforced by `mobile-gate`
- New or changed handwritten source files: **500 lines max** (Rust/Kotlin/Java).
- New or changed test files: **800 lines max**.
- New or changed GitHub workflow files: **300 lines max**.
- A normal PR: **800 added+deleted lines** and **20 changed files** at most.
- Files over **1 MiB** are rejected. Binary files over **256 KiB** require explicit exemption; APK/AAB/JAR/AAR/SO/ZIP/keystores are prohibited regardless of size.
- Generated code, lockfiles, Gradle wrapper JAR, and initialization PRs require documented, reviewed exception; there are **no silent exemptions**.
- Preferred targets: 300 source lines, 400 changed lines, 10 files per PR. Functions should usually stay below 50 lines; investigate functions over 100 lines.

## Branch and review rules
- Branches: `feat/*`, `fix/*`, `refactor/*`, `chore/*`, `experiment/*`.
- One focused PR per change; target **2–3**, ceiling **5** concurrent PRs.
- Squash merge only after passing current-head checks.
- Routine code can auto-merge once repository settings and main protection are active.
- Security, permissions, cryptography, release signing, CI controls, JNI memory safety, and dependency trust require manual review.
- Delete merged branches automatically via GitHub repository setting. Never auto-delete unmerged branches; review dormant PRs at 14 days.
- Do not force-push or directly commit to protected main.

## CI budget
- Exactly **one automatic workflow** for PRs: `Mobile gate`; one always-present required job `mobile-gate`.
- No parallel OS/SDK matrices on ordinary PRs; maximum **3 jobs**, **1 costly build**, **1 active build job** for an ordinary PR.
- Target under **10 minutes**; job timeout **20 minutes**.
- Android UI changes get policy/lint/build checks, Rust/JNI changes additionally receive Android-target Rust checks; unknown shared-build/security changes must fail closed, not silently skip.
- Emulator/device/integration tests belong to manually triggered release verification after they exist.
- Changing docs alone must not compile Android, but `mobile-gate` must still report success.
- Cancel obsolete PR runs, but not a currently executing release.
- PR workflows never get signing secrets, write tokens, or elevated `pull_request_target` authority.

## CI exceptions and evidence
- Initial repository bootstrap is explicitly exempt **only from the PR-wide change count**, not file size, binary, secret, or workflow count checks. This exception is restricted to `chore/repository-guardrails`; remove before enforcing future PRs or handle through an explicit manual allowlist.
- CI passing is not proof of a physical Android device pass, distributable APK, or Google Play release.
- Record benchmark evidence before raising resource or fan-out budgets.
