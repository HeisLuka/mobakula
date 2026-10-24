package dev.heisluka.mobakula

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** A dedicated provider entrypoint. Opening it never starts network I/O. */
@Composable
internal fun CloudSourcesScreen(
    onNoteImported: () -> Unit,
    onOpenNotes: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .testTag("cloud_sources_screen"),
        verticalArrangement = Arrangement.spacedBy(17.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Облако", style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold)
                Text("Настройте источник, проверьте содержимое и выберите, " +
                    "какие заметки сохранить на устройстве.",
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.bodyMedium)
                Text("Автоматической синхронизации нет",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        S3InvitePreviewCard(
            onNoteImported = onNoteImported,
            onOpenNotes = onOpenNotes,
        )
        HorizontalDivider()
        Text("Другие провайдеры",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            UnavailableCloudProvider("Dropbox", "DB", Modifier.weight(1f))
            UnavailableCloudProvider("S3 / MinIO", "S3", Modifier.weight(1f))
        }
        Text("Эти источники пока недоступны в Android Akula. " +
            "Настроить или проверить их здесь нельзя.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun UnavailableCloudProvider(name: String, mark: String, modifier: Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(mark, style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold)
            Text(name, style = MaterialTheme.typography.titleSmall)
            Text("Недоступно", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
