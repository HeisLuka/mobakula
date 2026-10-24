# Android Cloud tab - navigation and provider capabilities

## Why

The Files screen continues to handle local Markdown and text import/export,
without hosting the S3 profile or signed network requests. Cloud is now a
separate third bottom-navigation tab next to Notes and Files.

## Cloud screen

- The screen opens instantly. Opening Cloud, scrolling providers and dismissing
  a provider never initiates S3 HEAD/GET, creates notes or accepts an invite.
- Yandex Object Storage is the only active Android provider. Its tile opens
  the existing protected configuration sheet and verified read/manual one-note
  import flow from #48/#49.
- Dropbox and generic S3/MinIO appear strictly as static, visibly unavailable
  placeholders. They have no click handlers, no credential fields, no apparent
  active connection status, and no background retries.
- Back from Cloud or Files returns to the Notes tab. An editor still retains
  its normal guarded back behavior.
- Successful manual note receive first closes the Yandex overlay, then
  navigates to Notes. The verified local note list is refreshed.
- No cloud connection, automatic synchronization, ownership proof, remote
  write, deletion, or replace permission is introduced by the tab.

## Refactoring and acceptance

NotesFiles is extracted from NotesScreen to keep that already-large Compose
screen below the repository source-file growth limit. Its behavior and import/
export intents are unchanged.

S3CloudProviderUiTest is updated to prove the Yandex tile is not accessible
from Files, then navigate via Cloud and open the real Material 3 sheet. The
existing opt-in Android emulator runner executes that exact UI test and File
Gate cold-reopen regressions. The source-level smoke contract also checks
that the local Files component has no S3 invitation controls.

No live S3 credentials or remote network testing are required.
