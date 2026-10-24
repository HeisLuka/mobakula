package dev.heisluka.mobakula

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

/**
 * The cloud landing tile is deliberately neutral: a saved local credential
 * is NOT an S3 connection and never earns a green "synchronized" indicator.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun S3InvitePreviewCard(
    onNoteImported: () -> Unit, onOpenNotes: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    var opened by rememberSaveable { mutableStateOf(false) }
    var configured by remember(context) {
        mutableStateOf(S3ProtectedInviteStore(context).exists())
    }
    val sheetBusy = remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Облачные источники",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold)
        Text("Провайдеры открываются отдельно. Ваши локальные заметки " +
            "не отправляются в облако.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    Card(
        modifier = Modifier.fillMaxWidth().testTag("cloud_yandex_tile")
            .clickable { opened = true },
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    Text("S3", color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Yandex Object Storage", fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                Text("Ручной импорт Markdown · только чтение",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
                Text(
                    if (configured) "Профиль хранится на устройстве"
                    else "Настроить через .akula-sync",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text("›", color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.headlineMedium)
        }
    }
    if (opened) {
        val sheet = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden || !sheetBusy.value },
        )
        ModalBottomSheet(
            onDismissRequest = { if (!sheetBusy.value) opened = false },
            sheetState = sheet,
            modifier = Modifier.testTag("cloud_s3_sheet"),
        ) {
            S3InviteSettings(
                onNoteImported = onNoteImported,
                onOpenNotes = onOpenNotes,
                onConfigured = { configured = it },
                onBusyChanged = { sheetBusy.value = it },
            )
        }
    }
    }
}

/** Protected offline setup. The sheet is never a remote sync admission. */
@Composable
private fun S3InviteSettings(
    onNoteImported: () -> Unit,
    onOpenNotes: () -> Unit,
    onConfigured: (Boolean) -> Unit,
    onBusyChanged: (Boolean) -> Unit,
) {
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
    var readBusy by remember { mutableStateOf(false) }
    LaunchedEffect(busy, readBusy) { onBusyChanged(busy || readBusy) }

    // The private file content is not retained after closing the provider sheet.
    DisposableEffect(Unit) {
        onDispose { envelope?.fill(0) }
    }

    /** Opens a sealed invitation on a worker and never publishes credentials. */
    LaunchedEffect(store) {
        if (store.exists()) {
            busy = true
            onBusyChanged(true)
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
                onConfigured(saved)
                busy = false
                onBusyChanged(readBusy)
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
            onBusyChanged(true)
            scope.launch {
                try {
                    envelope = withContext(Dispatchers.IO) {
                        readBoundedInvitation(context, uri)
                    }
                } catch (_: Exception) {
                    failure = "Не удалось прочитать .akula-sync (максимум 16 КиБ)"
                } finally {
                    busy = false
                    onBusyChanged(readBusy)
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
                    onBusyChanged(true)
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { store.delete() }
                            saved = false
                            onConfigured(false)
                            preview = null
                            password = ""
                            envelope?.fill(0)
                            envelope = null
                            failure = null
                            notice = "Локальные настройки S3 удалены"
                        } catch (_: Exception) {
                            failure = "Не удалось удалить защищённые настройки"
                        } finally { busy = false; onBusyChanged(readBusy) }
                    }
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Отмена") }
            },
        )
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(bottom = 32.dp).testTag("cloud_s3_content"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Облачный источник", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary)
        Text("Yandex Object Storage",
            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Получайте отдельные Markdown-заметки. Akula проверит данные " +
            "перед созданием локальной копии. Автоматической синхронизации нет.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(Modifier.fillMaxWidth().padding(15.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(if (saved) "01  Защищённый профиль" else "01  Добавить профиль",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Text(
                    if (saved) "Конфигурация зашифрована Android Keystore. " +
                        "Это не означает успешного подключения к серверу."
                    else "Выберите приглашение .akula-sync, созданное на ПК, " +
                        "и введите его пароль. Удалённые данные пока не читаются.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            if (!saved) {
                OutlinedButton(enabled = !busy, onClick = {
                    picker.launch(arrayOf("*/*"))
                }) { Text("Выбрать файл .akula-sync") }
                if (envelope != null) {
                    Text("Приглашение выбрано · " + envelope!!.size + " байт",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
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
                        onBusyChanged(true)
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
                                    onConfigured(true)
                                    notice = "Профиль сохранён. Для чтения S3 запустите проверку."
                                    envelope?.fill(0)
                                    envelope = null
                                } else {
                                    failure = "Неверное приглашение или пароль: " +
                                        result.optString("code", "invalid_invitation")
                                }
                            } catch (_: Exception) {
                                failure = "Не удалось защитить настройки. Импорт не завершён."
                                saved = store.exists()
                                onConfigured(saved)
                            } finally {
                                temporary.fill('\u0000')
                                busy = false
                                onBusyChanged(readBusy)
                            }
                        }
                    }) { Text("Проверить и сохранить") }
                }
            } else {
                Text("Проверка содержимого доступна ниже.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
            }
        }
        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        failure?.let { text ->
            Surface(shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.errorContainer) {
                Text(text, Modifier.fillMaxWidth().padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        notice?.let { text ->
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
        }
        if (saved) {
            S3ReadOnlyProbeButton(
                store, enabled = !busy && preview != null,
                onImported = onNoteImported,
                onOpenNotes = onOpenNotes,
                onWorkingChanged = {
                    readBusy = it
                    onBusyChanged(busy || it)
                },
            )
        }
        preview?.let { p ->
            var details by remember { mutableStateOf(false) }
            OutlinedButton(onClick = { details = !details }) {
                Text(if (details) "Скрыть параметры источника" else "Параметры источника")
            }
            if (details) {
                Surface(shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Данные из приглашения, не подтверждённые сервером",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Бакет: " + p.optString("bucket"))
                        Text("Префикс: " + p.optString("prefix"))
                        Text("Cloud VaultId: " + p.optString("cloudVaultId"),
                            style = MaterialTheme.typography.bodySmall)
                        Text("Genesis: " + p.optString("claimedGenesisFingerprint"),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (saved) {
            HorizontalDivider()
            TextButton(enabled = !busy, onClick = { confirmDelete = true }) {
                Text("Удалить локальный S3-профиль",
                    color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
