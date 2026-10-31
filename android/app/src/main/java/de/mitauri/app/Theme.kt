package de.mitauri.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Earth tones taken from the mascot, with one accent. */
object MitauriColors {
    val Background = Color(0xFF141110)
    val Surface = Color(0xFF1E1A17)
    val Raised = Color(0xFF2A2420)
    val Hairline = Color(0xFF3A322C)
    val Ink = Color(0xFFF4EDE2)
    val Muted = Color(0xFFA99D8F)
    val Accent = Color(0xFFE08A45)
    val AccentSoft = Color(0xFFF0B98A)
    val AccentInk = Color(0xFF2A1608)
    val UserBubble = Color(0xFF4A2F1C)
    val Success = Color(0xFF8DB56F)
    val Warning = Color(0xFFE5B65C)
    val Danger = Color(0xFFDD5F4F)
}

private val typography = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
)

@Composable
fun MitauriTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = MitauriColors.Accent,
            onPrimary = MitauriColors.AccentInk,
            background = MitauriColors.Background,
            surface = MitauriColors.Surface,
            onSurface = MitauriColors.Ink,
            onBackground = MitauriColors.Ink,
            onSurfaceVariant = MitauriColors.Muted,
            surfaceVariant = MitauriColors.Raised,
            outline = MitauriColors.Hairline,
            error = MitauriColors.Danger,
        ),
        typography = typography,
        content = content,
    )
}
