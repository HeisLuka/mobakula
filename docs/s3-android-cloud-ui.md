# Android S3 UI — provider tile and protected setup sheet

## Navigation

Notes -> Files / Import & Export -> Cloud sources -> Yandex Object Storage.
The Yandex tile opens a Material 3 bottom sheet; it does not navigate
away from local notes or open a cloud filesystem directly. Android back
and dismiss close the sheet, but a running protected save, S3 verification,
checkpoint pin or manual receive guards against accidental dismissal.

## Visual state contract

- No configuration: "Configure via .akula-sync". Choose an invitation
  using Android SAF, enter a password and save only after native Core
  authenticated the invitation. Reject >16 KiB and wipe in-memory copies.
- Stored profile: "Profile stored on device". This is a neutral local
  credential state, not a green connected or synchronized indicator.
- The sheet explicitly states that importing is read-only from S3,
  one Markdown note at a time, and cannot authenticate the cloud owner.
- Initial sheet entry opens locally sealed configuration in a worker;
  it must NOT perform unrequested S3 HEAD/GET or auto-import anything.
- Source IDs and raw genesis appear only in optional expandable
  technical details, not in the main onboarding path.
- Removing the saved profile requires a separate confirmation and does
  not delete local notes or remote cloud objects.

## Acceptance

Instrumented S3CloudProviderUiTest launches the actual Activity,
opens the Files tab, scrolls to Yandex tile and opens the bottom sheet.
It asserts explicit "no automatic sync" text and absence of a
"Synced" state. It requires no keys, fixture or network access.

Further result/preview typography and candidate cards are implemented
separately, to keep the repository change-size guard unchanged.
