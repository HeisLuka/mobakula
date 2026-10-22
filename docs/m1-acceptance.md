# M1 — real local notes (Android)

## Implemented in this change
- User can create, list, open, edit and explicitly save a Markdown note.
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
4. Separate later work: debounced autosave, local recovery journal, search, stable signing for in-place APK upgrades, device telemetry, older API behavior.

## Known limitations
- Manual **Save** is required. An unsaved draft can be lost if Android kills the process: the UI displays an unsaved warning. Do not call this fully crash-resilient autosave.
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
