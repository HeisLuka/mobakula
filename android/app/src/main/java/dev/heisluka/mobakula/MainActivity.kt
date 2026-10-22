package dev.heisluka.mobakula

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

private val lightColors = lightColorScheme(
    primary = Color(0xFF086B69),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD4F2EE),
    onPrimaryContainer = Color(0xFF094F4E),
    background = Color(0xFFF5F8F7),
    surface = Color.White,
)

private val darkColors = darkColorScheme(
    primary = Color(0xFF78DDD1),
    onPrimary = Color(0xFF0C3938),
    primaryContainer = Color(0xFF164B4A),
    onPrimaryContainer = Color(0xFFD2F5EF),
    background = Color(0xFF101819),
    surface = Color(0xFF1B2526),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MobileApp() }
    }
}

@Composable
private fun MobileApp() {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColors else lightColors) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            NotesScreen()
        }
    }
}
