# One-time GitHub administration

Connector currently has write/push but not admin. The owner must configure these in repository settings after initial CI exists:

1. Settings → General → Pull Requests: **Allow squash merging ON**, **Allow merge commits OFF**, **Allow rebase merging OFF**, **Allow auto-merge ON**.
2. Settings → General → Pull Requests: **Automatically delete head branches ON**.
3. Settings → Rules → Rulesets: target `main`; require pull request and the exact required status context `mobile-gate` (select the first completed run); block force pushes and deletion.
4. Keep signed release credentials in protected environments only; do not make them available to PR workflow.
5. Do not require an unavailable second reviewer on a single-maintainer repository. Security/release/CI changes are never blindly auto-merged.

The PR workflow is one top-level Actions workflow and one job; no independent diagnostics or matrix. After the first local Android build is added, extend that same job with scope-aware compilation.

## Protected device-test APK signing

Create a GitHub environment named `device-test-signing` restricted to branch `main`. Store the long-lived **test-only** keystore and password in environment secrets; store approved alias and signer certificate SHA-256 in environment variables. See [device-test signing](device-test-signing.md). This is a separate manually-triggered workflow. Do not put a signing key in the repository, generic PR secrets, or artifacts. The connector has no admin rights to provision these secrets.

Before uploading *any* signing secret: [check main ruleset #25669997](https://github.com/HeisLuka/mobakula/rules/25669997) is still `enforcement=active` and requires PR, `mobile-gate`, and no force-push/deletion. The GitHub API reported **active** on 23 October 2026 after the earlier disabled state was corrected. The signer re-checks this policy on every run and fails closed if protection is weakened; only the repository admin can change it.
