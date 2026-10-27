package dev.heisluka.mobakula

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

class NotesViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = NotesRepository(application.applicationContext)
    private val draftStore = DraftRecoveryStore(application.applicationContext)
    private val checkpointIdle = DebouncedAutoSave(
        viewModelScope, ::checkpointAfterIdle, idleMillis = 200L,
    )

    var headers by mutableStateOf<List<NoteHeader>>(emptyList())
        private set
    var opened by mutableStateOf<NoteDocument?>(null)
        private set
    var draft by mutableStateOf("")
        private set
    var busy by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var info by mutableStateOf<String?>(null)
        private set
    var pendingCreateId by mutableStateOf<String?>(null)
        private set
    private var saveEvidence by mutableStateOf(NoteSaveEvidence.FILE_READ)
    private val unresolvedHistoryNotes = mutableSetOf<String>()
    private val autoSave = DebouncedAutoSave(viewModelScope, ::saveAfterIdle)
    private var autoSaveBlocked by mutableStateOf(false)
    var recoveredDraft by mutableStateOf<DraftRecoverySuggestion?>(null)
        private set
    var draftCheckpointReady by mutableStateOf(false)
        private set
    // "Later" preserves the older backup rather than overwriting it.
    private var preservedRecoveryId: String? = null
    var imeComposing by mutableStateOf(false)
        private set

    val dirty: Boolean get() = opened?.content != draft && opened != null
    val saveStatus: String get() = when {
        needsReview -> noteSaveStatus(dirty, saveEvidence)
        imeComposing && dirty -> "Ввод с клавиатуры не завершён — сохранение отложено"
        autoSaveBlocked && dirty -> "Автосохранение остановлено — сохраните вручную"
        dirty && preservedRecoveryId == opened?.id ->
            "Старая копия сохранена отдельно; новые изменения не резервируются"
        dirty && draftCheckpointReady ->
            "Черновик скопирован локально; основной файл ещё не сохранён"
        else -> noteSaveStatus(dirty, saveEvidence)
    }
    val canSave: Boolean get() = canSaveNote(dirty, saveEvidence) && !imeComposing
    val needsReview: Boolean get() = saveEvidence == NoteSaveEvidence.NEEDS_REVIEW

    init {
        viewModelScope.launch {
            try {
                repository.initialize()
                headers = repository.list()
                info = "Локальное хранилище открыто"
            } catch (cause: Exception) {
                error = cause.message ?: "Не удалось открыть заметки"
            } finally {
                busy = false
            }
        }
    }

    private fun task(onFailure: (() -> Unit)? = null, block: suspend () -> Unit) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            error = null
            try {
                block()
            } catch (cause: Exception) {
                onFailure?.invoke()
                info = null
                // An uncertain create may already exist on disk. Never display
                // it as fully saved just because the recovered text is clean.
                val affected = (cause as? NoteFailure)?.noteId
                if (affected != null) {
                    unresolvedHistoryNotes.add(affected)
                    if (opened == null) {
                        pendingCreateId = affected
                        val recovered = runCatching { repository.read(affected) }.getOrNull()
                        if (recovered != null) {
                            opened = recovered
                            draft = recovered.content
                        }
                    }
                    if (opened?.id == affected) {
                        saveEvidence = NoteSaveEvidence.NEEDS_REVIEW
                    }
                }
                error = if (affected != null) {
                    "Требуется проверка сохранения заметки $affected: ${cause.message}"
                } else cause.message ?: "Ошибка работы с заметкой"
            } finally {
                busy = false
            }
        }
    }

    fun refresh() = task {
        headers = repository.list()
        info = "Список обновлён"
    }

    fun create() = task {
        checkpointIdle.cancel()
        draftStore.invalidate()
        recoveredDraft = null
        preservedRecoveryId = null
        draftCheckpointReady = false
        preventDuplicateCreate()
        val created = repository.create()
        opened = created
        draft = created.content
        imeComposing = false
        saveEvidence = NoteSaveEvidence.VERIFIED
        headers = repository.list()
        info = "Заметка создана; файл и история проверены"
    }

    private fun preventDuplicateCreate() {
        if (pendingCreateId != null) {
            throw NoteFailure("needs_review",
                "Сначала проверьте существующую заметку $pendingCreateId; повторное создание может дать дубль.")
        }
    }

    fun open(id: String) = task {
        checkpointIdle.cancel()
        draftStore.invalidate()
        recoveredDraft = null
        preservedRecoveryId = null
        draftCheckpointReady = false
        val note = repository.read(id)
        opened = note
        draft = note.content
        imeComposing = false
        saveEvidence = if (id in unresolvedHistoryNotes) {
            NoteSaveEvidence.NEEDS_REVIEW
        } else {
            NoteSaveEvidence.FILE_READ
        }
        info = null
        val savedDraft = try {
            draftStore.load(note.id)
        } catch (cause: Exception) {
            info = "Резервная копия повреждена или недоступна; основной файл не изменён"
            null
        }
        if (savedDraft != null) {
            if (savedDraft.text == note.content) {
                // A process may die after a verified Core save but before cleanup.
                runCatching { draftStore.clearIfRevision(note.id, savedDraft.baseHash) }
            } else {
                recoveredDraft = DraftRecoverySuggestion(
                    savedDraft, changedOnDisk = savedDraft.baseHash != note.hash,
                )
            }
        }
    }

    fun inspectPendingCreate() {
        pendingCreateId?.let(::open)
    }

    fun importFile(uri: Uri) = task {
        checkpointIdle.cancel()
        draftStore.invalidate()
        recoveredDraft = null
        preservedRecoveryId = null
        draftCheckpointReady = false
        preventDuplicateCreate()
        val created = repository.importFile(uri)
        opened = created
        draft = created.content
        imeComposing = false
        saveEvidence = NoteSaveEvidence.VERIFIED
        headers = repository.list()
        info = "Файл импортирован; запись и история проверены"
    }

    fun exportFile(uri: Uri) = task {
        val current = opened ?: return@task
        if (dirty) throw NoteFailure("unsaved", "Перед экспортом сохраните изменения.")
        val result = repository.exportFile(current, uri)
        info = if (result == ExportStatus.VERIFIED) {
            "Файл записан и проверен повторным чтением"
        } else {
            "Экспорт записан, но проверка чтением недоступна. Проверьте файл вручную."
        }
    }

    fun exportSavedNote(id: String, uri: Uri) = task {
        // Export a fresh saved revision from Rust, never the unsaved editor draft.
        val current = repository.read(id)
        val result = repository.exportFile(current, uri)
        info = if (result == ExportStatus.VERIFIED) {
            "Экспорт проверен повторным чтением"
        } else {
            "Файл экспортирован, но проверить чтением не удалось. Проверьте его вручную."
        }
    }

    // Also fence the ViewModel draft boundary, not only the Compose callback.
    fun updateFromEditor(ownerNoteId: String, text: String, composing: Boolean) {
        if (editorInputAccepted(ownerNoteId, opened?.id, busy)) update(text, composing)
    }

    // Composition-only IME events must also reach this method. They can end a
    // composing session without changing the text, and should restart debounce.
    fun update(text: String, composing: Boolean = false) {
        if (!busy && opened != null) {
            imeComposing = composing
            draft = text
            draftStore.invalidate()
            checkpointIdle.cancel()
            draftCheckpointReady = false
            if (dirty && !imeComposing && preservedRecoveryId != opened?.id) {
                checkpointIdle.schedule()
            }
            info = if (dirty) "Есть несохранённые изменения" else null
            autoSave.cancel()
            if (canScheduleAutoSave(dirty, busy, saveEvidence, autoSaveBlocked, imeComposing)) {
                autoSave.schedule()
            }
        }
    }

    // TextFieldValue's composing region is not durable across editor recreation
    // (such as rotation). Do not leave the preserved ViewModel permanently blocked.
    fun onEditorRecreated() {
        if (!imeComposing) return
        imeComposing = false
        autoSave.cancel()
        if (dirty && preservedRecoveryId != opened?.id) checkpointIdle.schedule()
        if (canScheduleAutoSave(dirty, busy, saveEvidence, autoSaveBlocked, false)) {
            autoSave.schedule()
        }
    }

    /** Debounce a local safety copy separately from File Gate verified autosave. */
    private fun checkpointAfterIdle() {
        val current = opened ?: return
        if (!dirty || imeComposing || preservedRecoveryId == current.id) return
        val snapshot = DraftSnapshot(current.id, current.hash, draft)
        val generation = draftStore.epoch()
        viewModelScope.launch {
            try {
                val stored = draftStore.checkpoint(snapshot, generation)
                if (opened?.id == snapshot.noteId && draft == snapshot.text && dirty) {
                    if (stored) draftCheckpointReady = true
                    else if (generation == draftStore.epoch()) {
                        info = "Старая резервная копия другой ревизии сохранена отдельно"
                    }
                }
            } catch (cause: Exception) {
                if (opened?.id == snapshot.noteId && dirty && error == null) {
                    info = "Резервная копия черновика не записана; сохраните файл вручную"
                }
            }
        }
    }

    fun dismissRecoveredDraft() {
        preservedRecoveryId = recoveredDraft?.snapshot?.noteId
        recoveredDraft = null  // The on-disk copy is intentionally preserved.
    }

    fun removeRecoveredDraft() = task {
        val candidate = recoveredDraft ?: return@task
        draftStore.invalidate()
        draftStore.clearIfRevision(candidate.snapshot.noteId, candidate.snapshot.baseHash)
        recoveredDraft = null
        preservedRecoveryId = null
        draftCheckpointReady = false
        info = "Резервная копия удалена; основной файл не изменён"
    }

    fun restoreRecoveredDraft() = task {
        val candidate = recoveredDraft ?: return@task
        val current = opened ?: return@task
        // Never overwrite a newer revision; make the user copy conflicting text.
        val fresh = repository.read(current.id)
        if (candidate.snapshot.baseHash != fresh.hash) {
            recoveredDraft = candidate.copy(changedOnDisk = true)
            info = "Основной файл изменился; автоматическое восстановление запрещено"
            return@task
        }
        opened = fresh
        draft = candidate.snapshot.text
        imeComposing = false
        recoveredDraft = null
        preservedRecoveryId = null
        draftCheckpointReady = true  // Existing checkpoint remains on disk.
        draftStore.invalidate()
        checkpointIdle.schedule()
        autoSave.cancel()
        if (canScheduleAutoSave(dirty, false, saveEvidence, autoSaveBlocked, false)) {
            autoSave.schedule()
        }
        info = "Черновик восстановлен; основной файл ещё не сохранён"
    }

    private fun saveAfterIdle() {
        if (canScheduleAutoSave(dirty, busy, saveEvidence, autoSaveBlocked, imeComposing)) {
            persist(automatic = true)
        }
    }

    fun save() = persist(automatic = false)

    private fun persist(automatic: Boolean) {
        if (imeComposing) {
            if (!automatic) error = "Завершите ввод с системной клавиатуры перед сохранением"
            return
        }
        if (!automatic) autoSave.cancel()
        checkpointIdle.cancel()
        draftStore.invalidate()
        if (automatic && !canScheduleAutoSave(
                dirty, busy, saveEvidence, autoSaveBlocked, imeComposing,
            )) return
        task(onFailure = { if (automatic) autoSaveBlocked = true }) {
        val current = opened ?: return@task
        if (saveEvidence == NoteSaveEvidence.NEEDS_REVIEW) {
            throw NoteFailure("needs_review",
                "Результат предыдущей записи неоднозначен. Экспортируйте текущий файл; повторное сохранение пока запрещено.")
        }
        if (!dirty) {
            info = "Изменений нет"
            return@task
        }
        val snapshot = DraftSnapshot(current.id, current.hash, draft)
        // Keep a checkpoint even if File Gate fails after the attempt begins.
        // Failure to make the safety copy must not prevent a normal Core save.
        val checkpointed = if (preservedRecoveryId == current.id) false
            else runCatching { draftStore.writeBeforeSave(snapshot) }.getOrDefault(false)
        val saved = repository.save(current, draft)
        if (checkpointed) {
            runCatching { draftStore.clearIfRevision(current.id, current.hash) }
        }
        opened = saved
        draft = saved.content
        saveEvidence = NoteSaveEvidence.VERIFIED
        autoSaveBlocked = false
        draftCheckpointReady = false
        headers = repository.list()
        info = when {
            !checkpointed -> "Файл и история проверены; старая копия черновика сохранена отдельно"
            automatic -> "Автосохранение: файл и история проверены"
            else -> "Файл и история проверены"
        }
        }
    }

    fun discard() = discardChanges(exit = false)
    fun discardAndBack() = discardChanges(exit = true)

    private fun discardChanges(exit: Boolean) = task {
        val current = opened ?: return@task
        autoSave.cancel()
        checkpointIdle.cancel()
        draftStore.invalidate()
        // Delete only this revision's backup, never an earlier conflicting copy.
        if (preservedRecoveryId != current.id) {
            draftStore.clearIfRevision(current.id, current.hash)
        }
        draft = current.content
        autoSaveBlocked = false
        draftCheckpointReady = false
        imeComposing = false
        recoveredDraft = null
        if (exit) preservedRecoveryId = null
        if (exit) opened = null
        info = if (exit) null else "Изменения отменены"
    }

    fun back() {
        if (busy) return
        if (dirty) {
            error = "Сначала сохраните или отмените изменения"
        } else {
            autoSave.cancel()
            checkpointIdle.cancel()
            draftStore.invalidate()
            autoSaveBlocked = false
            draftCheckpointReady = false
            recoveredDraft = null
            preservedRecoveryId = null
            opened = null
            imeComposing = false
            saveEvidence = NoteSaveEvidence.FILE_READ
            info = null
            error = null
        }
    }
}
