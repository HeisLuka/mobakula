# One-time GitHub administration

Connector currently has write/push but not admin. The owner must configure these in repository settings after initial CI exists:

1. Settings → General → Pull Requests: **Allow squash merging ON**, **Allow merge commits OFF**, **Allow rebase merging OFF**, **Allow auto-merge ON**.
2. Settings → General → Pull Requests: **Automatically delete head branches ON**.
3. Settings → Rules → Rulesets: target `main`; require pull request and the exact required status context `mobile-gate` (select the first completed run); block force pushes and deletion.
4. Keep signed release credentials in protected environments only; do not make them available to PR workflow.
5. Do not require an unavailable second reviewer on a single-maintainer repository. Security/release/CI changes are never blindly auto-merged.

The PR workflow is one top-level Actions workflow and one job; no independent diagnostics or matrix. After the first local Android build is added, extend that same job with scope-aware compilation.
