package dev.heisluka.mobakula

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun NotesScreen(
    modifier: Modifier = Modifier,
    state: NotesViewModel = viewModel(),
) {
    BackHandler(enabled = state.opened != null) { state.back() }
    Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        state.info?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (state.busy) {
            CircularProgressIndicator()
        }
        val opened = state.opened
        if (opened == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = state::create, enabled = !state.busy) {
                    Text("Новая заметка")
                }
                OutlinedButton(onClick = state::refresh, enabled = !state.busy) {
                    Text("Обновить")
                }
            }
            if (state.headers.isEmpty() && !state.busy && state.error == null) {
                Text("Заметок пока нет. Создайте первую.")
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.headers, key = { it.id }) { entry ->
                    OutlinedButton(
                        onClick = { state.open(entry.id) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.busy,
                    ) { Text(entry.title.ifBlank { "Без названия" }) }
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = state::back, enabled = !state.busy) {
                    Text("К списку")
                }
                if (state.dirty) {
                    OutlinedButton(onClick = state::discard, enabled = !state.busy) {
                        Text("Отменить правки")
                    }
                }
            }
            OutlinedTextField(
                value = state.draft,
                onValueChange = state::update,
                modifier = Modifier.fillMaxWidth().weight(1f),
                enabled = !state.busy,
                minLines = 8,
                label = { Text("Текст заметки (Markdown)") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = state::save, enabled = !state.busy && state.dirty) {
                    Text("Сохранить")
                }
                Text(
                    if (state.dirty) "Не сохранено" else "Сохранено",
                    modifier = Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
