package dev.heisluka.mobakula

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
    val nativeVersion = remember { runCatching { NativeBridge.apiVersion() }.getOrNull() }
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Акула", style = MaterialTheme.typography.headlineMedium)
        Text("Локальные заметки · без интернета", style = MaterialTheme.typography.titleMedium)
        Text(
            nativeVersion?.let { "Rust JNI: API v$it" } ?: "Ошибка загрузки Rust JNI",
            style = MaterialTheme.typography.bodySmall,
        )
        NotesScreen(modifier = Modifier.weight(1f))
        Text(
            "Синхронизация и сервер появятся после испытаний локальной версии.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
