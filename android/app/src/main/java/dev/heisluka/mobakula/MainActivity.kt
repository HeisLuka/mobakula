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
    primary = AkulaPalette.Ocean,
    onPrimary = Color.White,
    primaryContainer = AkulaPalette.SeaGlass,
    onPrimaryContainer = Color(0xFF0B5770),
    background = Color(0xFFF6F9FC),
    surface = Color.White,
)

private val darkColors = darkColorScheme(
    primary = Color(0xFF78CFE9),
    onPrimary = Color(0xFF0C3938),
    primaryContainer = Color(0xFF1C4455),
    onPrimaryContainer = Color(0xFFDAF3FF),
    background = Color(0xFF101B25),
    surface = Color(0xFF172A35),
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
