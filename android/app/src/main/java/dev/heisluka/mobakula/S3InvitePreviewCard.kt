package dev.heisluka.mobakula

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

/** Structural and password-authenticated preview, NOT S3 access or pairing. */
@Composable
internal fun S3InvitePreviewCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var envelope by remember { mutableStateOf<ByteArray?>(null) }
    var password by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<JSONObject?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            password = ""
            envelope = null
            preview = null
            failure = null
            busy = true
            scope.launch {
                try {
                    envelope = withContext(Dispatchers.IO) {
                        readBoundedInvitation(context, uri)
                    }
                } catch (cause: Exception) {
                    failure = cause.message ?: "Не удалось прочитать файл"
                } finally {
                    busy = false
                }
            }
        }
    }

    Surface(shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("S3 — проверка конфигурации",
                style = MaterialTheme.typography.titleMedium)
            Text("Проверка зашифрованного .akula-sync без подключения. " +
                "Файлы пока не синхронизируются.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = !busy, onClick = {
                picker.launch(arrayOf("*/*"))
            }) { Text("Выбрать .akula-sync") }
            if (envelope != null) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it.take(1024) },
                    label = { Text("Пароль приглашения") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(enabled = !busy && password.isNotEmpty(), onClick = {
                    val temporary = password.toCharArray()
                    password = ""
                    busy = true
                    preview = null
                    failure = null
                    val bytes = envelope
                    scope.launch {
                        try {
                            val response = withContext(Dispatchers.IO) {
                                JSONObject(NativeBridge.inspectS3Invitation(
                                    requireNotNull(bytes), temporary))
                            }
                            if (response.optBoolean("ok")) {
                                preview = response.getJSONObject("data")
                            } else {
                                failure = "Проверка не пройдена: " +
                                    response.optString("code", "invalid_invitation")
                            }
                        } catch (cause: Exception) {
                            failure = "Ошибка проверки: " +
                                (cause.message ?: "неизвестная ошибка")
                        } finally {
                            temporary.fill('\u0000')
                            busy = false
                        }
                    }
                }) { Text("Проверить файл") }
            }
            if (busy) CircularProgressIndicator(Modifier.size(22.dp))
            failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            preview?.let { p ->
                Text("Конфигурация прочитана. Источник S3 НЕ подтверждён.",
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
