# M1 — real local notes (Android)

## Implemented in this change
- User can create, list, open, edit and save Markdown notes explicitly or after a 1.2-second idle window (debounced autosave).
- Kotlin/Compose renders the UI and calls a single JNI JSON request adapter.
- Rust NoteVault owns the app-private notes workspace. Mutations use **Akula Core File Gate create/write** with HistoryService and expected BLAKE3 revision hash.
- A save conflict, bad identifier, large note, or history warning is shown as an error. The editor does **not** silently overwrite a changed note.
- Save/read/write execute on Dispatchers.IO via the Android repository.
- ViewModel retains unsaved editor state across Activity rotation. Unsaved edits block navigation away from the editor; user must save or discard.
- No INTERNET permission and no dependency on server, account or cloud.

## Test evidence required
1. Rust unit: create → read → edit with revision → list → stale hash refused → reopen same files; malformed ID and oversized content rejected.
2. GitHub CI: one workflow / one job. Cargo tests, Rust Android arm64 and x86_64, Kotlin/Compose build, JNI packaged in APK.
3. Physical phone: create/edit/save; airplane mode; force-stop/relaunch; screen rotation with unsaved text; alert on unsaved back; confirm saved content is unchanged after restart.
4. Separate later work: crash-durable recovery journal, stable signing for in-place APK upgrades, device telemetry, older API behavior.

## Known limitations
- Auto-save starts after 1.2 seconds without typing, or the user can press **Save**. Edits can still be lost if Android kills the process before the verified file+history result. No crash-durable draft journal or operation receipt exists.
- Emulator proof: [GitHub Actions #39852740106](https://github.com/HeisLuka/mobakula/actions/runs/39852740106), Android API 35 x86_64, `NativeNotesRoundTripTest` **1/1 passed, 0 failed**. Android runtime loaded JNI; File Gate created, edited, read and listed a note, refused stale hashes, and verified no INTERNET permission.
- Slow emulator smoke is **manual opt-in**, Actions → Mobile gate → Run workflow → `android_runtime=true`; normal PR/push jobs do not run an emulator. The third-party action is pinned to an immutable commit.
- Physical Android handset acceptance is still pending: no proof yet for process death, update signing, battery or long-term note history.
- Android debug APK produced by isolated GitHub runners may have different debug signing identities between runs. **Do not uninstall an APK containing important notes to update it:** uninstalling destroys its private data. Stable update signing must be configured before data-bearing upgrade tests.
- Core portability has been proven for compile/link, not memory/battery performance, all Android versions or host custody.

## M1+ — user-selected file transfer (separate PR)
- Import opens a user-selected `.md` / `.txt` through Android Storage Access Framework. The bytes are strictly decoded as UTF-8 and limited to 1 MiB, then copied **as a new** note through the pinned Rust Akula File Gate and HistoryService. The original file is never modified.
- Export is allowed only for an explicitly saved note, opened again from Rust and checked against its displayed revision. Android writes the selected **external** document, then reads it back where permitted. If read-back is forbidden, the result is labelled **unverified**, not "saved and checked".
- These commands do not implement arbitrary JNI paths or a second Kotlin vault writer. No broad storage permission or network permission is requested.
- The Android unit tests must reject truncated/oversized UTF-8, malformed encodings and binary NUL bytes, while accepting Russian and emoji.

### Acceptance on physical Android
1. Import a UTF-8 Markdown file containing Russian text and emoji; open and edit the created Akula note, save, force-stop, reopen and read again.
2. Export that saved note, open it outside Akula and compare the content. If the provider denies readback, verify manually and keep the app warning.
3. Import invalid UTF-8 and a file above 1 MiB: app reports an error, does not create a note or silently truncate.
4. Attempt export with unsaved edits: action is disabled until explicit Save.
5. **Before uninstalling or reinstalling a debug APK**, export each important note. A per-note Markdown export is not a complete vault or history backup.

## M1+ — idle autosave (separate PR)
- Editor schedules a write after 1200 ms without typing, through `NotesRepository.save()` and the existing Rust Akula File Gate + HistoryService. No independent Kotlin vault writer or network.
- Any subsequent keystroke cancels and replaces the pending deadline. Manual Save, discard, or leaving a clean editor cancels an outstanding callback.
- A successful write must return the verified file+history revision before the UI reports completion. Conflict/unknown history remains visible; after an autosave error, automatic retries are stopped until an explicit Save or discard.
- The spinner disables editing while one File Gate write is in progress, preventing an old response from overwriting newer text.
- **Limit:** unsaved keystrokes may be lost if Android force-stops the process during the debounce window or while the write is in flight. A `VERIFIED` save is needed before the force-stop test; this is not an always-durable recovery journal.
- Unit tests assert one save after inactivity, cancellation on new typing/manual action, and fail-closed handling of uncertain history. The physical phone still requires explicit QA for background/rotation/force-stop and stable signing.

## M1 — cold process restart, isolated opt-in runtime probe

- The app/debug and androidTest APKs must **both** be installed on a test Android device (same signing identity). The probe never uninstalls, clears app data, or touches the real note root.
- For a local/physical device with ADB enabled, run `python3 scripts/android/cold_reopen_probe.py --ack-force-stop` (add `--serial DEVICE_ID` if needed). This explicitly force-stops Akula, so do not run while editing important notes.
- Phase 1 launches an instrumentation process, opens a dedicated directory under `noBackupFilesDir`, creates and saves a Unicode/Markdown note through JNI/File Gate, and atomically records its ID, BLAKE3 hash, content and process PID.
- The runner force-stops the target app. Phase 2 starts a **new process**, asserts a different PID, opens the same dedicated vault, reads and compares the saved content/hash, checks list inclusion, and rejects a stale-hash overwrite.
- Each phase must return exactly `OK (1 test)`. The normal instrumented suite skips both test stages without the explicit `akula_cold_phase` argument, so it cannot accidentally claim a cold restart from an in-process test.
- The workflow's manual opt-in `android_runtime=true` now runs the standard Android JNI smoke followed by this two-phase check on an API 35 x86_64 emulator. Ordinary PR/push CI only **compiles** the instrumentation APK; it does not prove process restart.
- This tests saved-note recovery only. Unsaved text before the debounce or a lost post-write acknowledgment, Android upgrade signing, and power-loss durability remain outside this probe. Physical handset PASS must be recorded separately.
