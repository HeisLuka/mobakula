package dev.heisluka.mobakula

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun NotesScreen(modifier: Modifier = Modifier, state: NotesViewModel = viewModel()) {
    var search by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableStateOf("notes") }
    var askDiscard by rememberSaveable { mutableStateOf(false) }
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current

    fun backToList() {
        if (state.dirty) askDiscard = true else {
            state.back()
            tab = "notes"
        }
    }

    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) state.importFile(uri) }
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri ->
        val chosen = exportId
        if (uri != null) {
            if (chosen != null) state.exportSavedNote(chosen, uri)
            else state.exportFile(uri)
        }
        exportId = null
    }

    BackHandler(enabled = state.opened != null && !state.busy) { backToList() }
    BackHandler(enabled = state.opened == null && tab == "files") { tab = "notes" }

    if (askDiscard) {
        AlertDialog(
            onDismissRequest = { askDiscard = false },
            title = { Text("Несохранённые изменения") },
            text = { Text("Вернуться без сохранения текста заметки?") },
            confirmButton = {
                TextButton(onClick = {
                    state.discardAndBack()
                    tab = "notes"
                    askDiscard = false
                }) { Text("Не сохранять") }
            },
            dismissButton = {
                TextButton(onClick = { askDiscard = false }) { Text("Остаться") }
            },
        )
    }

    state.recoveredDraft?.let { candidate ->
        AlertDialog(
            onDismissRequest = state::dismissRecoveredDraft,
            title = {
                Text(if (candidate.changedOnDisk)
                    "Черновик и основной файл различаются"
                    else "Найдена резервная копия черновика")
            },
            text = {
                Text(if (candidate.changedOnDisk)
                    "Файл изменился после создания копии. Автоматическое восстановление " +
                        "запрещено. Скопируйте текст, чтобы сохранить оба варианта."
                    else "После предыдущего запуска остались несохранённые изменения. " +
                        "Восстановление не перезаписывает файл до проверенного сохранения.")
            },
            confirmButton = {
                if (candidate.changedOnDisk) {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(candidate.snapshot.text))
                        state.dismissRecoveredDraft()
                    }) { Text("Скопировать текст") }
                } else {
                    TextButton(onClick = state::restoreRecoveredDraft) {
                        Text("Восстановить")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = state::dismissRecoveredDraft) { Text("Позже") }
                TextButton(onClick = state::removeRecoveredDraft) { Text("Удалить копию") }
            },
        )
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            if (state.opened == null) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    NavigationBarItem(
                        selected = tab == "notes",
                        onClick = { tab = "notes" },
                        icon = { Text("▤", style = MaterialTheme.typography.titleLarge) },
                        label = { Text("Заметки") },
                    )
                    NavigationBarItem(
                        selected = tab == "files",
                        onClick = { tab = "files" },
                        icon = { Text("⇅", style = MaterialTheme.typography.titleLarge) },
                        label = { Text("Файлы") },
                    )
                }
            }
        },
    ) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.opened == null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    AkulaBrand()
                    LocalPill()
                }
            }
            when {
                state.opened != null -> NotesEditor(
                    state, ::backToList,
                    onExport = {
                        exportId = null
                        state.opened?.let { exportPicker.launch("akula-" + it.id + ".md") }
                    },
                    modifier = Modifier.weight(1f),
                )
                tab == "files" -> NotesFiles(
                    state = state,
                    onImport = { importPicker.launch(arrayOf("text/*", "application/octet-stream")) },
                    onExport = { id ->
                        exportId = id
                        exportPicker.launch("akula-" + id + ".md")
                    },
                    modifier = Modifier.weight(1f),
                )
                else -> NotesOverview(
                    state, search, { search = it },
                    modifier = Modifier.weight(1f),
                )
            }
            if (state.busy) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text("Обработка…", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state.opened == null || state.error != null) {
                StatusMessage(state.error, state.info)
            }
            if (state.opened == null) {
                Text("Тестовая версия: при удалении приложения локальные заметки исчезнут. " +
                    "Экспортируйте важные файлы перед переустановкой.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun NotesOverview(
    state: NotesViewModel,
    search: String,
    onSearchChange: (String) -> Unit,
    modifier: Modifier,
) {
    val query = search.trim()
    val notes = state.headers.filter {
        query.isEmpty() || it.title.contains(query, ignoreCase = true)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Мои заметки", style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold)
                Text(state.headers.size.toString() + " локальных заметок",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalButton(onClick = state::refresh, enabled = !state.busy) {
                Text("Обновить")
            }
        }
        OutlinedTextField(
            value = search, onValueChange = onSearchChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Поиск по названиям") },
            leadingIcon = { Text("⌕", style = MaterialTheme.typography.titleLarge) },
            singleLine = true, shape = RoundedCornerShape(16.dp),
        )
        if (state.pendingCreateId != null) {
            StatusMessage("Создание заметки требует проверки истории. Не повторяйте создание.", null)
            TextButton(onClick = state::inspectPendingCreate, enabled = !state.busy) {
                Text("Открыть существующую заметку")
            }
        }
        Box(Modifier.fillMaxSize()) {
            if (notes.isEmpty() && !state.busy) {
                Column(Modifier.align(Alignment.Center).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (query.isEmpty()) "Заметок пока нет" else "Ничего не найдено",
                        style = MaterialTheme.typography.titleLarge)
                    Text(if (query.isEmpty()) "Создайте первую заметку кнопкой +."
                        else "Попробуйте другой запрос.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 86.dp)) {
                    items(notes, key = { it.id }) { note ->
                        Card(modifier = Modifier.fillMaxWidth().clickable(enabled = !state.busy) {
                            state.open(note.id)
                        }, shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                            Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Surface(shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer) {
                                    Text("▤", Modifier.padding(12.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.titleLarge)
                                }
                                Column(Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(note.title.ifBlank { "Без названия" },
                                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold)
                                    LocalPill()
                                }
                                Text("›", style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
            if (!state.busy && state.pendingCreateId == null) {
                FloatingActionButton(
                    onClick = state::create,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).testTag("note_create"),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Text("+", style = MaterialTheme.typography.headlineMedium)
                }
            }

        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotesEditor(
    state: NotesViewModel,
    onBack: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier,
) {
    // TextFieldValue owns cursor, selection and IME composition; the ViewModel
    // remains the only source for persisted content and autosave decisions.
    var editor by rememberSaveable(state.opened?.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(state.draft))
    }
    // One bounded undo history per opened note. Rust HistoryService remains durable.
    val editHistory = remember(state.opened?.id) { DraftEditHistory() }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val keyboardVisible = WindowInsets.isImeVisible
    LaunchedEffect(state.opened?.id) { state.onEditorRecreated() }
    LaunchedEffect(state.draft) {
        // File Gate may normalize a saved text or Discard may restore content.
        // Do not reset the current caret on a selection-only IME event.
        if (editor.text != state.draft) {
            editHistory.clear()
            val length = state.draft.length
            editor = editor.copy(
                text = state.draft,
                selection = TextRange(
                    editor.selection.start.coerceIn(0, length),
                    editor.selection.end.coerceIn(0, length),
                ),
                composition = null,
            )
        }
    }

    fun format(command: MarkdownCommand) {
        if (state.busy || state.imeComposing) return
        val updated = applyMarkdownCommand(editor, command)
        if (updated.text != editor.text) editHistory.record(editor, updated)
        editor = updated
        if (updated.text != state.draft || (updated.composition != null) != state.imeComposing) {
            state.update(updated.text, composing = updated.composition != null)
        }
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    fun restore(value: TextFieldValue?) {
        if (state.busy || state.imeComposing || value == null) return
        editor = value
        if (value.text != state.draft || (value.composition != null) != state.imeComposing) {
            state.update(value.text, composing = value.composition != null)
        }
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    val title = state.draft.lineSequence().firstOrNull()
        ?.trimStart('#', ' ')?.take(65)?.ifBlank { "Новая заметка" } ?: "Новая заметка"
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack, enabled = !state.busy,
                modifier = Modifier.testTag("note_back")) { Text("← Назад") }
            Button(onClick = state::save, enabled = !state.busy && state.canSave,
                modifier = Modifier.testTag("note_save")) {
                Text("Сохранить")
            }
        }
        if (!keyboardVisible) {
            Text(title, style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(state.saveStatus,
            color = if (state.dirty || state.needsReview) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = editor,
            onValueChange = { next ->
                // Ignore a late IME callback while File Gate is saving.
                if (!state.busy) {
                    if (next.text != editor.text) editHistory.record(editor, next)
                    editor = next
                    if (next.text != state.draft ||
                        (next.composition != null) != state.imeComposing
                    ) {
                        state.update(next.text, composing = next.composition != null)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().weight(1f)
                .focusRequester(focusRequester).testTag("note_editor"),
            enabled = !state.busy,
            shape = RoundedCornerShape(18.dp),
            textStyle = MaterialTheme.typography.bodyLarge,
            label = { Text("Содержимое Markdown") },
            minLines = if (keyboardVisible) 2 else 8,
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AssistChip(
                onClick = { restore(editHistory.undo(editor)) },
                enabled = !state.busy && !state.imeComposing && editHistory.canUndo,
                label = { Text("↶", style = MaterialTheme.typography.labelLarge) },
                modifier = Modifier.semantics { contentDescription = "Отменить изменение" },
            )
            AssistChip(
                onClick = { restore(editHistory.redo(editor)) },
                enabled = !state.busy && !state.imeComposing && editHistory.canRedo,
                label = { Text("↷", style = MaterialTheme.typography.labelLarge) },
                modifier = Modifier.semantics { contentDescription = "Повторить изменение" },
            )
            MarkdownCommand.entries.forEach { command ->
                AssistChip(
                    onClick = { format(command) },
                    enabled = !state.busy && !state.imeComposing,
                    label = { Text(command.glyph, style = MaterialTheme.typography.labelLarge) },
                    modifier = Modifier.semantics {
                        contentDescription = command.description
                    },
                )
            }
        }
        if (!keyboardVisible) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = state::discard,
                    enabled = !state.busy && state.dirty) { Text("Отменить") }
                OutlinedButton(onClick = onExport,
                    enabled = !state.busy && !state.dirty) { Text("Экспорт .md") }
            }
            Text("Автосохранение после паузы ввода. Резервная копия " +
                "черновика создаётся отдельно, но не заменяет проверенное сохранение.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NotesFiles(
    state: NotesViewModel,
    onImport: () -> Unit,
    onExport: (String) -> Unit,
    modifier: Modifier,
) {
    var selectExport by rememberSaveable { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Text("Импорт и экспорт", style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold)
        FileActionCard("↑", "Импорт .md / .txt",
            "Добавить файл в локальные заметки.",
            onImport, enabled = !state.busy && state.pendingCreateId == null)
        FileActionCard("↓", "Экспорт",
            "Выбрать заметку и сохранить её копию.",
            { selectExport = !selectExport }, enabled = !state.busy)
        if (selectExport) {
            Text("Выберите заметку для экспорта", style = MaterialTheme.typography.titleMedium)
            LazyColumn(Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.headers, key = { it.id }) { note ->
                    TextButton(onClick = { onExport(note.id) }, enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(note.title.ifBlank { "Без названия" }, modifier = Modifier.weight(1f),
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("↓")
                    }
                }
            }
        } else {
            Surface(shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Локальное хранилище", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    Text("Заметки хранятся на этом устройстве и доступны без интернета.",
                        style = MaterialTheme.typography.bodyMedium)
                    Text("Экспорт отдельной заметки — не резервная копия всей истории.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            S3InvitePreviewCard()
        }
    }
}

@Composable
private fun StatusMessage(error: String?, info: String?) {
    val message = error ?: info ?: return
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        color = if (error != null) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.primaryContainer) {
        Text(message, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (error != null) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onPrimaryContainer)
    }
}
