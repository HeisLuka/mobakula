package dev.heisluka.mobakula

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Read-only candidate cards; only File Gate may authorize an actual import. */
@Composable
internal fun S3CandidateCards(
    result: S3ReadOnlyProbe.Report,
    enabled: Boolean,
    onSelect: (S3ManualImport.Candidate) -> Unit,
) {
    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("all") }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
                            enabled = enabled,
                            onClick = { onSelect(candidate) },
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
    }
}
