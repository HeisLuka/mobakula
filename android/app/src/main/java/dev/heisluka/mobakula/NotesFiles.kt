package dev.heisluka.mobakula

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun NotesFiles(
    state: NotesViewModel,
    onImport: () -> Unit,
    onExport: (String) -> Unit,
    modifier: Modifier,
) {
    var selectExport by rememberSaveable { mutableStateOf(false) }
    Column(if (selectExport) modifier else modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(13.dp)) {
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
        }
    }
}

