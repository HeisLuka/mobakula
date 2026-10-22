package dev.heisluka.mobakula

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
    var selected by rememberSaveable { mutableStateOf("local") }
    val nativeVersion = remember { runCatching { NativeBridge.apiVersion() }.getOrNull() }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Акула", style = MaterialTheme.typography.headlineMedium)
        Text("Мобильное приложение · первый запуск", style = MaterialTheme.typography.bodyMedium)
        Text(
            nativeVersion?.let { "Rust JNI: API v$it" } ?: "Ошибка загрузки Rust JNI",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeButton("Локально", selected == "local") { selected = "local" }
            ModeButton("Связь", selected == "connect") { selected = "connect" }
            ModeButton("Сервер", selected == "server") { selected = "server" }
        }
        when (selected) {
            "local" -> {
                Text("Мои заметки", style = MaterialTheme.typography.titleLarge)
                Text("Офлайн-хранилище будет подключено к общему ядру Rust на следующем этапе.")
                Button(onClick = {}, enabled = false) { Text("Создать заметку") }
            }
            "connect" -> {
                Text("Подключить устройство", style = MaterialTheme.typography.titleLarge)
                Text("Сопряжение с Акулой на ПК пока недоступно.")
            }
            else -> {
                Text("Создать сервер", style = MaterialTheme.typography.titleLarge)
                Text("Установщик защищённого сервера пока недоступен.")
            }
        }
        Text(
            "M0: интерфейс доступен без интернета. Сохранение заметок ещё не реализовано.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ModeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}
