package dev.heisluka.mobakula

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal object AkulaPalette {
    val Ocean = Color(0xFF087C9D)
    val SeaGlass = Color(0xFFE8F6FC)
    val Mint = Color(0xFFE8F8F2)
    val DeepNavy = Color(0xFF162435)
}

@Composable
internal fun AkulaBrand(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val color = MaterialTheme.colorScheme.primary
        Canvas(Modifier.size(37.dp)) {
            val fin = Path().apply {
                moveTo(size.width * .08f, size.height * .83f)
                cubicTo(size.width * .29f, size.height * .31f,
                    size.width * .58f, size.height * .07f,
                    size.width * .91f, size.height * .04f)
                cubicTo(size.width * .72f, size.height * .36f,
                    size.width * .77f, size.height * .57f,
                    size.width * .86f, size.height * .74f)
                cubicTo(size.width * .59f, size.height * .59f,
                    size.width * .28f, size.height * .65f,
                    size.width * .08f, size.height * .83f)
                close()
            }
            drawPath(fin, color)
        }
        Text("Akula", style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
internal fun LocalPill(modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(40.dp),
        color = MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.size(6.dp).background(Color(0xFF23996B), CircleShape))
            Text("Локально", color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun FileActionCard(
    symbol: String,
    title: String,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer) {
                Text(symbol, modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary)
        }
    }
}
