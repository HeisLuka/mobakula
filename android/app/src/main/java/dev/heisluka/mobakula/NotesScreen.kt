package dev.heisluka.mobakula

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun NotesScreen(
    modifier: Modifier = Modifier,
    state: NotesViewModel = viewModel(),
) {
    var search by rememberSaveable { mutableStateOf("") }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }

    fun returnToList() {
        if (state.dirty) confirmDiscard = true else state.back()
    }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        uri -> if (uri != null) state.importFile(uri)
    }
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri -> if (uri != null) state.exportFile(uri) }

    BackHandler(enabled = state.opened != null && !state.busy) { returnToList() }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Несохранённые изменения") },
            text = { Text("Изменения в заметке не сохранены. Вернуться к списку без них?") },
            confirmButton = {
                TextButton(onClick = {
                    state.discard()
                    state.back()
                    confirmDiscard = false
                }) { Text("Не сохранять") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Продолжить") }
            },
        )
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Акула", style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold)
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text("Только на устройстве",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }

            if (state.opened == null) {
                NotesOverview(
                    state = state,
                    search = search,
                    onSearchChange = { search = it },
                    onImport = { importPicker.launch(arrayOf("text/*", "application/octet-stream")) },
                    modifier = Modifier.weight(1f),
                )
            } else {
                NotesEditor(
                    state = state,
                    onBack = ::returnToList,
                    onExport = {
                        val current = state.opened
                        if (current != null) exportPicker.launch("akula-" + current.id + ".md")
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            if (state.busy) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                    Text("Обработка…", style = MaterialTheme.typography.bodySmall)
                }
            }
            StatusMessage(state.error, state.info)
            Text(
                "Предварительная версия: удаление приложения удаляет локальные заметки. " +
                    "Экспортируйте важные .md перед переустановкой.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NotesOverview(
    state: NotesViewModel,
    search: String,
    onSearchChange: (String) -> Unit,
    onImport: () -> Unit,
    modifier: Modifier,
) {
    val query = search.trim()
    val notes = state.headers.filter { query.isEmpty() || it.title.contains(query, ignoreCase = true) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("Мои заметки", style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold)
                Text(state.headers.size.toString() + " заметок",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium)
            }
            Button(onClick = state::create, enabled = !state.busy && state.pendingCreateId == null) { Text("+ Создать") }
        }

        OutlinedTextField(
            value = search,
            onValueChange = onSearchChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Найти заметку") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
        )

        TextButton(onClick = onImport, enabled = !state.busy && state.pendingCreateId == null) {
            Text("Импортировать Markdown или TXT")
        }
        if (state.pendingCreateId != null) {
            Text(
                "Предыдущее создание завершилось неоднозначно. Проверьте существующий файл, " +
                    "не повторяйте создание: история ещё не подтверждена.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = state::inspectPendingCreate, enabled = !state.busy) {
                Text("Открыть существующую заметку")
            }
        }

        if (notes.isEmpty() && !state.busy) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (query.isEmpty()) "Пока нет заметок" else "Ничего не найдено",
                        style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (query.isEmpty()) "Создайте первую заметку или импортируйте файл."
                        else "Попробуйте другое слово.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 20.dp),
            ) {
                items(notes, key = { it.id }) { note ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !state.busy) {
                            state.open(note.id)
                        },
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    ) {
                        Column(modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(note.title.ifBlank { "Без названия" },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium)
                            Text("Markdown · Локальная заметка",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotesEditor(
    state: NotesViewModel,
    onBack: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, enabled = !state.busy) { Text("← К заметкам") }
            Text(
                state.saveStatus,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
                style = MaterialTheme.typography.labelSmall,
                color = if (state.dirty || state.needsReview) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedTextField(
            value = state.draft,
            onValueChange = state::update,
            modifier = Modifier.fillMaxWidth().weight(1f),
            enabled = !state.busy,
            minLines = 10,
            label = { Text("Текст заметки · Markdown") },
            shape = RoundedCornerShape(18.dp),
            textStyle = MaterialTheme.typography.bodyLarge,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = state::save, enabled = !state.busy && state.canSave) {
                Text("Сохранить")
            }
            OutlinedButton(onClick = state::discard, enabled = !state.busy && state.dirty) {
                Text("Отменить правки")
            }
        }

        TextButton(onClick = onExport, enabled = !state.busy && !state.dirty) {
            Text("Экспортировать .md")
        }
        Text(
            "После паузы ввода Акула автоматически сохраняет заметку. " +
                "До подтверждения File Gate правки считаются несохранёнными.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusMessage(error: String?, info: String?) {
    val message = error ?: info ?: return
    val danger = error != null
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (danger) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.primaryContainer,
    ) {
        Text(message,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (danger) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onPrimaryContainer)
    }
}
