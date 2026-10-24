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
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current.applicationContext
    val checkpoints = remember(context) { S3CheckpointStore(context) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Button(enabled = enabled && !working, onClick = {
            working = true
            report = null
            failure = null
            scope.launch {
                try {
                    report = withContext(Dispatchers.IO) {
                        S3ReadOnlyProbe.run(store, checkpoints)
                    }
                } catch (_: S3CheckpointProbe.CheckpointMismatch) {
                    failure = "S3 вернул старую либо расходящуюся историю. " +
                        "Контрольная точка защищена: импорт заблокирован."
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
        report?.takeIf { it.bindingScan.available }?.let { result ->
            val binding = result.bindingScan
            Text(
                "Постоянные привязки S3: " +
                    "совпадающих " + binding.exact +
                    ", устаревших " + binding.outdated +
                    ", незавершённых " + binding.prepared +
                    ", заблокированных " + binding.held +
                    ", ещё не привязанных " + binding.unmapped + ". " +
                    (if (!binding.journalExists) {
                        "Журнал привязок ещё не создавался. "
                    } else {
                        "Это сохранённые намерения, а не разрешение на импорт. "
                    }) +
                    "Никакие заметки не изменены.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        report?.let { result ->
            Text(
                when (result.checkpointRelation) {
                    "first" -> "На устройстве нет закреплённой истории S3."
                    "unchanged" -> "Локальная контрольная точка S3 совпадает."
                    "advanced" -> "Новая история содержит закреплённую контрольную точку."
                    else -> "История S3 пока не закреплена."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            val proposal = result.checkpointProposal
            if (proposal != null && result.checkpointRelation != "unchanged") {
                Button(enabled = enabled && !working, onClick = {
                    working = true
                    failure = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                checkpoints.pinVerified(proposal.candidate, proposal.expectedPrior)
                            }
                            report = result.copy(
                                checkpointRelation = "unchanged",
                                checkpointProposal = null,
                            )
                        } catch (_: Exception) {
                            failure = "Не удалось закрепить S3 checkpoint. " +
                                "Повторите проверку без изменения файлов."
                        } finally {
                            working = false
                        }
                    }
                }) { Text("Закрепить проверенное состояние S3") }
                Text(
                    "Закрепление защищает от обнаруживаемого отката удалённой " +
                        "истории на этом устройстве, но не подтверждает владельца " +
                        "S3 и не разрешает запись заметок.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        failure?.let {
            Text(it, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
