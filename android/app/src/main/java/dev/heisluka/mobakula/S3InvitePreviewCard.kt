package dev.heisluka.mobakula

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException

private const val MAX_INVITATION_BYTES = 16 * 1024

/** SAF read only. No local pathname or write permission is granted to Akula. */
private fun readBoundedInvitation(context: Context, uri: Uri): ByteArray {
    val stream = context.contentResolver.openInputStream(uri)
        ?: throw IOException("Не удалось открыть выбранный файл")
    return stream.use {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(2048)
        var emptyReads = 0
        while (true) {
            val count = it.read(buffer)
            if (count < 0) break
            if (count == 0) {
                if (++emptyReads > 8) throw IOException("Не удалось дочитать файл")
                continue
            }
            emptyReads = 0
            if (output.size() + count > MAX_INVITATION_BYTES) {
                throw IOException("Файл превышает 16 КиБ")
            }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
}

/** Protected offline import only; never asserts remote owner trust or file Sync. */
@Composable
internal fun S3InvitePreviewCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { S3ProtectedInviteStore(context.applicationContext) }
    var envelope by remember { mutableStateOf<ByteArray?>(null) }
    var password by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<JSONObject?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(store.exists()) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    /** Opens a sealed invitation on a worker and never publishes credentials. */
    LaunchedEffect(store) {
        if (store.exists()) {
            busy = true
            try {
                val result = withContext(Dispatchers.IO) {
                    store.open().use { secret ->
                        JSONObject(NativeBridge.inspectS3Invitation(
                            secret.encryptedInvitation, secret.password))
                    }
                }
                if (result.optBoolean("ok")) {
                    preview = result.getJSONObject("data")
                    notice = "Профиль защищён Android Keystore. S3 ещё не подключён."
                } else {
                    failure = "Сохранённый профиль не прошёл проверку. Удалите и импортируйте заново."
                }
            } catch (_: Exception) {
                failure = "Невозможно открыть защищённый профиль. Удалите и импортируйте заново."
            } finally {
                saved = store.exists()
                busy = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null && !saved) {
            password = ""
            envelope?.fill(0)
            envelope = null
            preview = null
            failure = null
            notice = null
            busy = true
            scope.launch {
                try {
                    envelope = withContext(Dispatchers.IO) {
                        readBoundedInvitation(context, uri)
                    }
                } catch (_: Exception) {
                    failure = "Не удалось прочитать .akula-sync (максимум 16 КиБ)"
                } finally {
                    busy = false
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить сохранённый S3-профиль?") },
            text = { Text("Локальная защищённая конфигурация и её ключ будут удалены. " +
                "Заметки не изменятся, облако не затрагивается.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    busy = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { store.delete() }
                            saved = false
                            preview = null
                            password = ""
                            envelope?.fill(0)
                            envelope = null
                            failure = null
                            notice = "Локальные настройки S3 удалены"
                        } catch (_: Exception) {
                            failure = "Не удалось удалить защищённые настройки"
                        } finally { busy = false }
                    }
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Отмена") }
            },
        )
    }

    Surface(shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("S3 — настройки облака", style = MaterialTheme.typography.titleMedium)
            Text("Импорт .akula-sync с ПК. Данные сохраняются под ключом Android Keystore. " +
                "Подключение и синхронизация пока выключены.",
                style = MaterialTheme.typography.bodySmall)
            if (!saved) {
                OutlinedButton(enabled = !busy, onClick = {
                    picker.launch(arrayOf("*/*"))
                }) { Text("Выбрать .akula-sync") }
                if (envelope != null) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it.take(1024) },
                        label = { Text("Пароль конфигурации") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(enabled = !busy && password.isNotEmpty(), onClick = {
                        val temporary = password.toCharArray()
                        password = ""
                        busy = true
                        failure = null
                        notice = null
                        preview = null
                        val chosen = envelope
                        scope.launch {
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    val bytes = requireNotNull(chosen)
                                    val response = JSONObject(
                                        NativeBridge.inspectS3Invitation(bytes, temporary))
                                    if (response.optBoolean("ok")) {
                                        store.saveNew(bytes, temporary)
                                    }
                                    response
                                }
                                if (result.optBoolean("ok")) {
                                    preview = result.getJSONObject("data")
                                    saved = true
                                    notice = "Настройки защищены. Соединение S3 ещё не проверено."
                                    envelope?.fill(0)
                                    envelope = null
                                } else {
                                    failure = "Неверное приглашение или пароль: " +
                                        result.optString("code", "invalid_invitation")
                                }
                            } catch (_: Exception) {
                                failure = "Не удалось защитить настройки. Импорт не завершён."
                                saved = store.exists()
                            } finally {
                                temporary.fill('\u0000')
                                busy = false
                            }
                        }
                    }) { Text("Проверить и сохранить") }
                }
            } else {
                S3ReadOnlyProbeButton(store, enabled = !busy && preview != null)
                Text("Конфигурация сохранена локально. Для замены сначала удалите её.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(enabled = !busy, onClick = { confirmDelete = true }) {
                    Text("Удалить локальный S3-профиль")
                }
            }
            if (busy) CircularProgressIndicator(Modifier.size(22.dp))
            failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            preview?.let { p ->
                Text("Параметры из приглашения (НЕ подтверждены сервером):",
                    style = MaterialTheme.typography.bodySmall)
                Text("Провайдер: " + p.optString("provider"))
                Text("Бакет: " + p.optString("bucket"))
                Text("Префикс: " + p.optString("prefix"))
                Text("Cloud VaultId: " + p.optString("cloudVaultId"))
                Text("Заявленный отпечаток: " +
                    p.optString("claimedGenesisFingerprint"),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
