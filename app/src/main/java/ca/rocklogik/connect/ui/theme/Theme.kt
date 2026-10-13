package ca.rocklogik.connect.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The approved wireframes' palette (ROCKLOGIK-81): high contrast for a cab, one accent. */
object Rl {
    val Ink = Color(0xFF16191C)
    val Ground = Color(0xFFF3F4F1)
    val Card = Color(0xFFFFFFFF)
    val Rule = Color(0xFFD5D8D2)
    val RuleSoft = Color(0xFFE4E6E1)
    val Muted = Color(0xFF4F555A)
    val Accent = Color(0xFFC25E00)
    val AccentDown = Color(0xFFA44F00)
    val Ok = Color(0xFF1D6B3E)
    val Warn = Color(0xFF8A5300)
    val Fail = Color(0xFFA4231C)
    val InfoBg = Color(0xFFE3EDF7)
    val InfoInk = Color(0xFF123A5E)
    val WarnBg = Color(0xFFFFF4E2)
    val WarnRule = Color(0xFFE2B979)
    val Night = Color(0xFF0E1012)
    val OnNight = Color(0xFFE6E8E3)
}

@Composable
fun RockLogikTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Rl.Accent,
            onPrimary = Color.White,
            background = Rl.Ground,
            onBackground = Rl.Ink,
            surface = Rl.Card,
            onSurface = Rl.Ink,
            error = Rl.Fail,
        ),
        content = content,
    )
}
