# Android S4d0 — read-only additive receive preflight

This is a planning step, not file import, trust grant or synchronization.
It stacks on #37 with exact-root ancestry, index, revision JSON and all
content blobs already checked (BLAKE3 and lengths) by the same short-lived
native session. No alternative permissive network or JSON write path.

## What is checked

- Parse and recheck the complete RevisionDag in portable Rust.
- For every document, select its sole current head; historical versions do
  not become separate notes. Forked documents are held, not auto-merged.
- Content and rename current heads may become provisional candidates.
  Tombstones are held, never interpreted as local delete permission.
- In this initial mobile subset, only top-level .md files whose exact
  content is UTF-8 with LF-only newlines and no NUL may qualify. Nested
  paths, other extensions, binary and CRLF records are review cases.
- Reverify the already-downloaded blob set against immutable hashes/sizes;
  never trust remote filenames as local output paths.
- JNI exposes only candidate and review counts. Android explicitly states
  local filesystem collisions have not been inspected, owner identity is not
  independently proven and import remains unauthorized.

## Important unresolved gates before any File Gate create

1. Independently confirm remote publisher authority and persist a
   rollback-resistant, origin-bound head checkpoint; matching bytes and
   invitation password alone are not sufficient.
2. Bind the desktop DocumentId to an Android-owned note identity without
   overwriting any existing local notes, history or unsaved draft.
3. Inspect local application vault using the Core File Gate read/preflight
   contract, and hold any local collision, unresolved fork or tombstone.
4. Require an explicit user action for importing new files, then create
   them only with File Gate create-if-absent and independently re-read
   HistoryService. Treat uncertain publication as HOLD, never blindly retry.
5. Real-device validation of storage, Keystore, app restart, network races,
   role/ownership and interruption before accepting first import.

## Limits and no-false-success rules

The preflight only runs when #37 successfully checks every immutable blob
in its budget: 16 distinct blobs, 1 MiB each, 8 MiB combined, plus up to
64 revisions. Larger snapshots remain metadata-only; they do not show a
false zero import count. A changed root, missing body, bad hash, unsupported
DAG or expired session aborts the entire proof.

Tests cover ancestor selection, forks, tombstones, binary, nested, CRLF,
NUL, missing blob and empty Markdown. This is not a local file mutation
or remote sync receipt; writable=false, remote_owner_verified=false,
local_conflicts_checked=false in the Core plan.
