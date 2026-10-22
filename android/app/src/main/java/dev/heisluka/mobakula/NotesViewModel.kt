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

    val dirty: Boolean get() = opened?.content != draft && opened != null
    val saveStatus: String get() =
        if (autoSaveBlocked && dirty && !needsReview) {
            "Автосохранение остановлено — сохраните вручную"
        } else noteSaveStatus(dirty, saveEvidence)
    val canSave: Boolean get() = canSaveNote(dirty, saveEvidence)
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
        preventDuplicateCreate()
        val created = repository.create()
        opened = created
        draft = created.content
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
        val note = repository.read(id)
        opened = note
        draft = note.content
        saveEvidence = if (id in unresolvedHistoryNotes) {
            NoteSaveEvidence.NEEDS_REVIEW
        } else {
            NoteSaveEvidence.FILE_READ
        }
        info = null
    }

    fun inspectPendingCreate() {
        pendingCreateId?.let(::open)
    }

    fun importFile(uri: Uri) = task {
        preventDuplicateCreate()
        val created = repository.importFile(uri)
        opened = created
        draft = created.content
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

    fun update(text: String) {
        if (!busy && opened != null) {
            draft = text
            info = if (dirty) "Есть несохранённые изменения" else null
            autoSave.cancel()
            if (canScheduleAutoSave(dirty, busy, saveEvidence, autoSaveBlocked)) {
                autoSave.schedule()
            }
        }
    }

    private fun saveAfterIdle() {
        if (canScheduleAutoSave(dirty, busy, saveEvidence, autoSaveBlocked)) {
            persist(automatic = true)
        }
    }

    fun save() = persist(automatic = false)

    private fun persist(automatic: Boolean) {
        if (!automatic) autoSave.cancel()
        if (automatic && !canScheduleAutoSave(dirty, busy, saveEvidence, autoSaveBlocked)) return
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
        val saved = repository.save(current, draft)
        opened = saved
        draft = saved.content
        saveEvidence = NoteSaveEvidence.VERIFIED
        autoSaveBlocked = false
        headers = repository.list()
        info = if (automatic) "Автосохранение: файл и история проверены"
            else "Файл и история проверены"
        }
    }

    fun discard() {
        val current = opened ?: return
        if (busy) return
        autoSave.cancel()
        autoSaveBlocked = false
        draft = current.content
        error = null
        info = "Изменения отменены"
    }

    fun back() {
        if (busy) return
        if (dirty) {
            error = "Сначала сохраните или отмените изменения"
        } else {
            autoSave.cancel()
            autoSaveBlocked = false
            opened = null
            saveEvidence = NoteSaveEvidence.FILE_READ
            info = null
            error = null
        }
    }
}
