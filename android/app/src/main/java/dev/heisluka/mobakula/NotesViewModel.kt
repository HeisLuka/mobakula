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

    val dirty: Boolean get() = opened?.content != draft && opened != null

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

    private fun task(block: suspend () -> Unit) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            error = null
            try {
                block()
            } catch (cause: Exception) {
                info = null
                // A primary create can succeed while HistoryService fails.
                // Recover the existing ID rather than inviting another create.
                val affected = (cause as? NoteFailure)?.noteId
                if (affected != null && opened == null) {
                    val recovered = runCatching { repository.read(affected) }.getOrNull()
                    if (recovered != null) {
                        opened = recovered
                        draft = recovered.content
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
        val created = repository.create()
        opened = created
        draft = created.content
        headers = repository.list()
        info = "Заметка создана и записана"
    }

    fun open(id: String) = task {
        val note = repository.read(id)
        opened = note
        draft = note.content
        info = null
    }

    fun importFile(uri: Uri) = task {
        val created = repository.importFile(uri)
        opened = created
        draft = created.content
        headers = repository.list()
        info = "Файл импортирован как отдельная заметка Акулы"
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

    fun update(text: String) {
        if (!busy && opened != null) {
            draft = text
            info = if (dirty) "Есть несохранённые изменения" else "Сохранено"
        }
    }

    fun save() = task {
        val current = opened ?: return@task
        if (!dirty) {
            info = "Изменений нет"
            return@task
        }
        val saved = repository.save(current, draft)
        opened = saved
        draft = saved.content
        headers = repository.list()
        info = "Сохранено в Акуле"
    }

    fun discard() {
        val current = opened ?: return
        if (busy) return
        draft = current.content
        error = null
        info = "Изменения отменены"
    }

    fun back() {
        if (busy) return
        if (dirty) {
            error = "Сначала сохраните или отмените изменения"
        } else {
            opened = null
            info = null
            error = null
        }
    }
}
