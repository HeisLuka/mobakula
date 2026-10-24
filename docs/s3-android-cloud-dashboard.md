# Android S3 UI — verified read summary and manual Markdown cards

## Design contract

The cloud source sheet has an explicit, linear flow:

1. Local profile is imported through Android SAF and stored under Keystore.
2. The user explicitly starts read-only HTTPS S3 verification.
3. A verified read shows a concise generation/document/blob summary with
   an expandable technical-details panel. No green online/synced badge.
4. First/advanced history requires a user click to pin the checkpoint.
   After pinning, the UI **clears the stale report and requires another
   actual S3 read**. It never changes the old report to "unchanged".
5. Only a subsequent unchanged pinned read with complete blob proof,
   checked local slots, available SQLite binding scan and new Markdown
   candidates offers one-at-a-time import cards.
6. Each card shows a neutral Markdown label, short DocumentId and bounded
   byte length — it must not invent a title absent from the Core metadata.
   An explicit confirmation opens one new File Gate note only.
7. Verified local import displays a completion panel and a navigation
   action to the user's Notes tab.

## Fail-closed UX

- S3 timeout, 403/404/412, changed HEAD and incomplete proof are not
  displayed as imported, synced or cloud-owner authenticated.
- A mapped document's duplicate refusal is separate from transport errors.
- Ambiguous File Gate/SQLite failures instruct the user to inspect notes and
  the HOLD journal rather than automatically retry.
- No background sync, S3 PUT, tombstone processing, overwrite, auto-import
  or cloud-owner verification is introduced by these UI changes.

## Tests

Pure JVM S3CloudUiPolicyTest exercises every gate on the import/pin
buttons and neutral sizes. Android Compose instrumentation in the parent
UI PR checks tile navigation and absence of a "Synchronized" label.
Mobile gate also builds Rust, two JNI ABIs and the Android APK. Live
Yandex transport E2E remains opt-in and unproven without test credentials.
