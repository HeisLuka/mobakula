package dev.heisluka.mobakula

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.IOException

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MobileHome()
                }
            }
        }
    }
}

@Composable
private fun MobileHome() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    var fileName by rememberSaveable { mutableStateOf("Заметка.md") }
    var status by rememberSaveable { mutableStateOf("Новый текст. Для сохранения выберите место.") }
    var savedText by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by rememberSaveable { mutableStateOf("local") }
    val nativeVersion = remember { runCatching { NativeBridge.apiVersion() }.getOrNull() }

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { openMarkdown(context.contentResolver, uri) }
                .onSuccess { opened ->
                    text = opened.text
                    savedText = opened.text
                    fileName = opened.name
                    status = "Файл прочитан. Для записи изменений сохраните копию."
                }.onFailure { status = "Ошибка открытия: ${it.message}" }
        }
    }
    val saveFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        if (uri != null) {
            val snapshot = text
            runCatching { exportMarkdown(context.contentResolver, uri, snapshot) }
                .onSuccess { result ->
                    if (result == ExportResult.VERIFIED) {
                        savedText = snapshot
                        status = "Копия записана и проверена чтением."
                    } else {
                        status = "Провайдер подтвердил запись, но проверить копию не удалось."
                    }
                }.onFailure { status = "Ошибка сохранения: ${it.message}" }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Акула", style = MaterialTheme.typography.headlineMedium)
        Text("Локальный Markdown-редактор · без хранилища и истории Акулы")
        Text(nativeVersion?.let { "Rust JNI: API v$it" } ?: "Ошибка загрузки Rust JNI")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeButton("Текст", selected == "local") { selected = "local" }
            ModeButton("Связь", selected == "connect") { selected = "connect" }
            ModeButton("Сервер", selected == "server") { selected = "server" }
        }
        when (selected) {
            "local" -> {
                Text(fileName, style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { openFile.launch(arrayOf("text/*", "application/octet-stream")) }) {
                        Text("Открыть .md")
                    }
                    Button(onClick = { saveFile.launch(fileName) }) {
                        Text("Сохранить копию")
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { new ->
                        if (new.toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES) {
                            text = new
                            status = "Изменения не сохранены."
                        } else {
                            status = "Превышен предел 128 КиБ."
                        }
                    },
                    label = { Text("Текст Markdown") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 12,
                )
                Text(if (savedText == text) "Текст совпадает с проверенной копией."
                     else "Текущие изменения ещё не подтверждены сохранением.")
                Text(status, style = MaterialTheme.typography.bodySmall)
                Text("Это ручной файловый редактор. Автосохранение и история ещё не работают.")
            }
            "connect" -> {
                Text("Подключение к устройству", style = MaterialTheme.typography.titleMedium)
                Text("Недоступно. Сначала реализуем локальные заметки через Akula Core.")
            }
            else -> {
                Text("Свой сервер", style = MaterialTheme.typography.titleMedium)
                Text("Недоступно. Настройка сервера пока не входит в мобильную версию.")
            }
        }
    }
}

@Composable
private fun ModeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick) { Text(label) }
    else OutlinedButton(onClick = onClick) { Text(label) }
}
