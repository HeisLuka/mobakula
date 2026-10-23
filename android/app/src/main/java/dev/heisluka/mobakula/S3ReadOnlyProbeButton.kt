package dev.heisluka.mobakula

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * No connected/synced badge: an S3 response is not an owner identity proof,
 * and matching genesis bytes are not permission to mutate local files.
 */
@Composable
internal fun S3ReadOnlyProbeButton(store: S3ProtectedInviteStore, enabled: Boolean) {
    var working by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<S3ReadOnlyProbe.Report?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(modifier = Modifier.fillMaxWidth()) {
        Button(enabled = enabled && !working, onClick = {
            working = true
            report = null
            failure = null
            scope.launch {
                try {
                    report = withContext(Dispatchers.IO) {
                        S3ReadOnlyProbe.run(store)
                    }
                } catch (_: Exception) {
                    failure = "S3 проверка чтения не пройдена. " +
                        "Проверьте ключи, сеть, удалённую историю и время устройства."
                } finally {
                    working = false
                }
            }
        }) {
            Text("Проверить S3 (только чтение)")
        }
        if (working) CircularProgressIndicator(Modifier.padding(8.dp))
        report?.let { result ->
            Text(
                "Цепочка S3 прочитана: поколение " + result.latestGeneration +
                    ", манифестов " + result.manifestsChecked +
                    ", проверено наборов " + result.validatedShards +
                    ", документов " + result.distinctDocumentIds +
                    (if (result.metadataPreviewSupported) {
                        ", проверено метаданных ревизий " +
                            result.metadataRevisionsChecked + ". "
                    } else {
                        ". Метаданные ревизий не проверены: " +
                            "превышен лимит предпросмотра. "
                    }) +
                    (if (result.contentPreviewSupported) {
                        "Проверено содержимого: " + result.verifiedBlobCount +
                            " объектов, " + result.verifiedBlobBytes + " байт. "
                    } else {
                        "Содержимое заметок не проверено (лимит предпросмотра). "
                    }) +
                    "Владелец сервера отдельно не подтверждён. " +
                    "Синхронизация заметок не включена.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        report?.takeIf { it.contentPreviewSupported }?.let { result ->
            Text(
                "Предварительный план приёма: " +
                    result.receiveCandidatesPreview +
                    " Markdown-документов подходят для дальнейшей проверки, " +
                    result.receiveNeedsReviewPreview +
                    " требуют разбора. Локальные пересечения ещё не проверены. " +
                    "Импорт не разрешён и ничего не записывает.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        report?.takeIf { it.localSlotsChecked }?.let { result ->
            Text(
                "Сверка локальных UUID-слотов: отсутствуют " +
                    result.localSlotsAbsent + ", одинаковое содержимое без привязки " +
                    result.localSlotsIdenticalUnbound + ", конфликты " +
                    result.localSlotsConflicting + ", небезопасные объекты " +
                    result.localSlotsUnsafe + ". " +
                    "Это не разрешение импорта: идентичность источника и " +
                    "локальная привязка ещё не подтверждены.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        failure?.let {
            Text(it, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
