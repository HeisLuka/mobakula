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
4. Separate later work: debounced autosave, local recovery journal, import/export, search, stable signing for in-place APK upgrades, device telemetry, older API behavior.

## Known limitations
- Manual **Save** is required. An unsaved draft can be lost if Android kills the process: the UI displays an unsaved warning. Do not call this fully crash-resilient autosave.
- No physical-device or emulator evidence until tested. Compiled JNI symbols are not proof of runtime loading.
- Android debug APK produced by isolated GitHub runners may have different debug signing identities between runs. **Do not uninstall an APK containing important notes to update it:** uninstalling destroys its private data. Stable update signing must be configured before data-bearing upgrade tests.
- Core portability has been proven for compile/link, not memory/battery performance, all Android versions or host custody.
