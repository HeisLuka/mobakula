# Android S3 - per-document receive status and safe selection

The previous probe only reported aggregate counts (unmapped, exact,
outdated, prepared, held), so the UI could offer the "Import" action
next to an already-bound document. Native File Gate rejected it, but
the UI could not explain why before performing another network read.

## New read-only state inventory

The existing verified S3 read-session JNI probe now returns a bounded
documentStates array with exactly one entry per provisional Markdown
DocumentId. Entries contain ONLY Core-typed remote DocumentId and one
of: unmapped, exact, outdated, prepared, held. Never return the local
note UUID, user paths, text, S3 keys, cloud credentials or remote paths.

Native derives each state from the source-scoped SQLite journal and
the local File Gate readback. Bound data are "exact" only when local
bytes still match the original verified source BLAKE3. A local edit,
unavailable note or ambiguous record becomes HOLD; never report it as
safe for replacement. A remote newer revision is "outdated", and does
not authorize an implicit update.

Kotlin validates: maximum sixteen documents; valid typed IDs; no
duplicate IDs; exact correspondence between the state vector and
aggregate counts; no owner verification, synced or automatic import.
The current remote candidate set MUST exactly match the state map.

## New UI states

- New/unmapped: offer separate manual import after another fresh
  pinned S3 read and explicit consent.
- Already received/exact: show status; suppress duplicate import.
- Outdated: show the newer remote version; no overwrite/rebase.
- Prepared: show unfinished operation, do not blindly retry.
- Held: warn that manual review is required, do not auto-recover.

All candidate cards can be filtered by ID (only verified typed IDs,
not invented filenames/titles) and by New vs Received/Review.

The UI cannot elevate a state into File Gate write permission. Rust
still verifies the selected immutable bytes, pinned history and fresh
source before any additive local note create.

## Acceptance

JVM tests exercise distinct statuses and count/identity consistency,
including forged "synced" or unknown statuses. The existing Mobile
gate compiles Kotlin/JNI on Android and Rust and runs an opt-in KVM
UI smoke. Real Yandex data transfer remains independently pending.

## Independent Android UI acceptance

Candidate list rendering was extracted to S3CandidateCards, reducing the
existing network/probe composable. S3CandidateCardsUiTest renders an exact
synthetic two-document verified report (new vs already bound) without
JNI, S3 credentials or cloud access and asserts:

- Both statuses appear initially, but only the new document has an
  import action.
- Filtering New hides the bound document and still permits selecting
  only the new DocumentId.
- Filtering Received / Review hides all import actions.

The opt-in Android KVM runtime run invokes this test separately from
the Cloud provider navigation test and existing File Gate smoke.
