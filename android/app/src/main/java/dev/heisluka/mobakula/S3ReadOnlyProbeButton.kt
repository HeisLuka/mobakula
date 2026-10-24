package dev.heisluka.mobakula

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * User-facing read/receive workflow. Nothing becomes "synced" merely because
 * S3 returns a valid hash or because the local checkpoint was pinned.
 */
@Composable
internal fun S3ReadOnlyProbeButton(
    store: S3ProtectedInviteStore,
    enabled: Boolean,
    onImported: () -> Unit,
    onOpenNotes: () -> Unit = {},
    onWorkingChanged: (Boolean) -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val checkpoints = remember(context) { S3CheckpointStore(context) }
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<S3ReadOnlyProbe.Report?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var showDetails by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<S3ManualImport.Candidate?>(null) }
    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("all") }

    fun markWorking(value: Boolean) {
        working = value
        onWorkingChanged(value)
    }

    Column(
        Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HorizontalDivider()
        Text("02  Проверить источник", style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold)
        Text("Akula прочитает неизменяемые данные S3, проверит цепочку " +
            "ревизий и контрольные суммы. Файлы на устройстве не изменятся.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(
            enabled = enabled && !working,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                markWorking(true)
                report = null
                error = null
                notice = null
                scope.launch {
                    try {
                        report = withContext(Dispatchers.IO) {
                            S3ReadOnlyProbe.run(store, checkpoints)
                        }
                    } catch (_: S3CheckpointProbe.CheckpointMismatch) {
                        error = "История S3 расходится с закреплённой на этом устройстве. " +
                            "Импорт заблокирован."
                    } catch (_: Exception) {
                        error = "Проверка не завершилась. Проверьте сеть, ключи, " +
                            "содержимое S3 и время устройства."
                    } finally {
                        markWorking(false)
                    }
                }
            },
        ) {
            Text(if (report == null) "Проверить содержимое S3" else "Проверить повторно")
        }
        if (working) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Проверяем историю и байты. Не закрывайте панель.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        error?.let { message ->
            Surface(shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.errorContainer) {
                Text(message, Modifier.fillMaxWidth().padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        notice?.let { message ->
            Surface(shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer) {
                Text(message, Modifier.fillMaxWidth().padding(12.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        report?.let { result ->
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (result.contentPreviewSupported) "Содержимое проверено"
                        else "Проверена только структура S3",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        S3Metric(result.latestGeneration.toString(), "Поколение",
                            Modifier.weight(1f))
                        S3Metric(result.distinctDocumentIds.toString(), "Документы",
                            Modifier.weight(1f))
                        S3Metric(result.verifiedBlobCount.toString(), "Blob",
                            Modifier.weight(1f))
                    }
                    Text(
                        if (result.contentPreviewSupported)
                            "Проверено " + readableS3Bytes(result.verifiedBlobBytes) +
                                ". Данные не удостоверяют владельца сервера."
                        else "Предпросмотр ограничен размером набора. " +
                            "Импортировать непроверенное содержимое нельзя.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (result.receiveNeedsReviewPreview > 0 ||
                        result.bindingScan.held > 0) {
                        Text("Требуют разбора: " +
                            (result.receiveNeedsReviewPreview + result.bindingScan.held) +
                            ". Akula не перезаписывает спорные заметки.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { showDetails = !showDetails }) {
                        Text(if (showDetails) "Скрыть детали проверки"
                            else "Технические детали")
                    }
                    if (showDetails) {
                        HorizontalDivider()
                        Text("Манифестов: " + result.manifestsChecked +
                            " · Shard: " + result.validatedShards +
                            " · Ревизий: " + result.metadataRevisionsChecked,
                            style = MaterialTheme.typography.bodySmall)
                        Text("Локальные слоты: отсутствуют " + result.localSlotsAbsent +
                            ", конфликты " + result.localSlotsConflicting +
                            ", небезопасные " + result.localSlotsUnsafe,
                            style = MaterialTheme.typography.bodySmall)
                        val scan = result.bindingScan
                        Text("Привязки: совпадают " + scan.exact +
                            ", неактуальны " + scan.outdated +
                            ", незавершённые " + scan.prepared +
                            ", HOLD " + scan.held + ", новые " + scan.unmapped,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text("03  История на устройстве",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            Text(
                when (result.checkpointRelation) {
                    "first" -> "Это первая проверка источника. Закрепите историю, " +
                        "чтобы обнаруживать последующие откаты."
                    "advanced" -> "История изменилась, но содержит прежнюю " +
                        "контрольную точку. Подтвердите новое состояние."
                    "unchanged" -> "Контрольная точка совпала с сохранённой. " +
                        "Вы можете выбрать отдельную проверенную заметку."
                    else -> "История не закреплена; импорт пока недоступен."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (result.canOfferCheckpointPin()) {
                val proposal = result.checkpointProposal
                if (proposal != null) {
                    OutlinedButton(
                        enabled = enabled && !working,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            markWorking(true)
                            error = null
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        checkpoints.pinVerified(
                                            proposal.candidate, proposal.expectedPrior
                                        )
                                    }
                                    // Pinning is NOT a fresh network observation.
                                    // Do not fake "unchanged" or leave stale cards active.
                                    selected = null
                                    report = null
                                    notice = "История закреплена. Проверьте S3 ещё раз " +
                                        "перед выбором заметки."
                                } catch (_: Exception) {
                                    error = "Не удалось закрепить историю. " +
                                        "Ничего не импортировано."
                                } finally {
                                    markWorking(false)
                                }
                            }
                        },
                    ) { Text("Закрепить проверенную историю") }
                }
            }
            if (result.importedLocalNoteId != null) {
                Surface(shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.primaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(15.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Заметка добавлена локально",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        Text("Создана отдельная Markdown-заметка. " +
                            "Никакая синхронизация не включена.",
                            style = MaterialTheme.typography.bodySmall)
                        Button(onClick = onOpenNotes) { Text("Перейти к заметкам") }
                    }
                }
            } else if (result.checkpointRelation == "unchanged" &&
                result.contentPreviewSupported && result.bindingScan.available &&
                result.manualImportCandidates.isNotEmpty()) {
                Text("04  Проверенные Markdown",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Text("Локальный журнал проверен. Новые документы доступны по одному; " +
                    "ранее полученные и спорные документы нельзя импортировать повторно.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it.take(64) },
                    label = { Text("Поиск по идентификатору") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((id, label) in listOf(
                        "all" to "Все", "new" to "Новые", "review" to "Полученные / спорные"
                    )) {
                        FilterChip(selected = filter == id,
                            onClick = { filter = id }, label = { Text(label) })
                    }
                }
                val shown = result.manualImportCandidates.filter { candidate ->
                    val state = result.bindingScan.states[candidate.documentId]
                    candidate.documentId.contains(search.trim(), ignoreCase = true) &&
                        (filter == "all" || (filter == "new" &&
                            state == S3BindingProbe.State.UNMAPPED) ||
                            (filter == "review" &&
                                state != S3BindingProbe.State.UNMAPPED))
                }
                if (shown.isEmpty()) {
                    Text("По текущему фильтру документов нет",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                shown.forEach { candidate ->
                    val state = result.bindingScan.states[candidate.documentId]
                        ?: S3BindingProbe.State.HELD
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp,
                            MaterialTheme.colorScheme.outlineVariant),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("MD", style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f)) {
                                    Text("Markdown · " + candidate.documentId.take(8),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold)
                                    Text(readableS3Bytes(candidate.bytes.toLong()) +
                                        " · " + state.displayStatus(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        overflow = TextOverflow.Ellipsis, maxLines = 2)
                                }
                            }
                            if (result.canOfferCandidate(candidate)) {
                                OutlinedButton(
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = enabled && !working,
                                    onClick = { selected = candidate },
                                ) { Text("Получить локальную копию") }
                            } else {
                                Text("Повторный импорт недоступен. " +
                                    "Локальная заметка не изменяется.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            } else if (result.manualImportCandidates.isNotEmpty() &&
                result.importedLocalNoteId == null) {
                Text("Импорт недоступен: проверьте и закрепите историю либо " +
                    "разберите существующие локальные привязки.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        Surface(shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Text("S3 — внешний источник. Владелец сервера отдельно не " +
                "подтверждён. Действие ручное, без удаления и перезаписи заметок.",
                Modifier.fillMaxWidth().padding(12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall)
        }
    }

    selected?.let { candidate ->
        AlertDialog(
            onDismissRequest = { if (!working) selected = null },
            title = { Text("Получить одну заметку?") },
            text = {
                Text("Akula ещё раз проверит историю, ревизию и содержимое " +
                    "Markdown (" + readableS3Bytes(candidate.bytes.toLong()) +
                    "). Будет создана новая локальная заметка, без замены " +
                    "существующих файлов. Владелец S3 не удостоверен.\n\n" +
                    "Документ: " + candidate.documentId)
            },
            confirmButton = {
                Button(
                    enabled = enabled && !working &&
                        (report?.canOfferCandidate(candidate) == true),
                    onClick = {
                        selected = null
                        markWorking(true)
                        report = null
                        error = null
                        notice = null
                        scope.launch {
                            try {
                                val checked = withContext(Dispatchers.IO) {
                                    S3ReadOnlyProbe.run(store, checkpoints, candidate)
                                }
                                report = checked
                                if (checked.importedLocalNoteId != null) onImported()
                            } catch (_: S3ManualImport.ExistingSourceBinding) {
                                error = "Эта заметка уже получена. Новая копия не создана."
                            } catch (_: S3CheckpointProbe.CheckpointMismatch) {
                                error = "История источника изменилась. Импорт отменён."
                            } catch (e: Exception) {
                                error = if (e.message?.contains("receive_needs_review") == true)
                                    "Результат записи неопределён. Проверьте заметки " +
                                        "и журнал; не повторяйте импорт автоматически."
                                else "Импорт не завершён. Проверьте сеть и источник. " +
                                    "Существующие заметки не перезаписывались."
                            } finally {
                                markWorking(false)
                            }
                        }
                    },
                ) { Text("Импортировать") }
            },
            dismissButton = {
                TextButton(onClick = { selected = null }, enabled = !working) {
                    Text("Отмена")
                }
            },
        )
    }
}

@Composable
private fun S3Metric(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}
