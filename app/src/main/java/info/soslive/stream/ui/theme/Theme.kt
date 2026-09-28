package info.soslive.stream.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SosRed = Color(0xFFD32F2F)
private val SosRedDark = Color(0xFFFF6659)

private val LightColors = lightColorScheme(
    primary = SosRed,
    onPrimary = Color.White,
    secondary = Color(0xFF455A64),
    onSecondary = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = SosRedDark,
    onPrimary = Color.Black,
    secondary = Color(0xFF90A4AE),
    onSecondary = Color.Black,
)

@Composable
fun SosLiveTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}
