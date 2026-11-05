package app.liferecord

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 视觉风格：安静深绿 / 米白、正文优先、阅读耐看。
 * 辅以高对比度危险色（红橙）与柔和中性灰，避免仅依赖颜色识别状态。
 */
private val DeepPineLight = Color(0xFF1E3A2F)
private val LightGreenPrimary = Color(0xFF264E3F)
private val LightGreenOnPrimary = Color(0xFFFFFFFF)
private val LightGreenPrimaryContainer = Color(0xFFD3E7DC)
private val LightGreenOnPrimaryContainer = Color(0xFF072017)

private val LightBackground = Color(0xFFF9F9F6) // 米白偏温暖纸质
private val LightSurface = Color(0xFFFFFFFF)
private val LightSurfaceVariant = Color(0xFFEBECE6) // 卡片与分割背景
private val LightOnSurface = Color(0xFF1D201E)
private val LightOnSurfaceVariant = Color(0xFF4C544F)
private val LightOutline = Color(0xFF88928B)
private val LightOutlineVariant = Color(0xFFD2D6D2)

private val DeepPineDark = Color(0xFF85B5A1)
private val DarkGreenPrimary = Color(0xFF90C2AD)
private val DarkGreenOnPrimary = Color(0xFF003827)
private val DarkGreenPrimaryContainer = Color(0xFF1B4F3D)
private val DarkGreenOnPrimaryContainer = Color(0xFFB8EDD7)

private val DarkBackground = Color(0xFF121513)
private val DarkSurface = Color(0xFF191D1A)
private val DarkSurfaceVariant = Color(0xFF252B27)
private val DarkOnSurface = Color(0xFFE1E4DF)
private val DarkOnSurfaceVariant = Color(0xFFA5ACA5)
private val DarkOutline = Color(0xFF717972)
private val DarkOutlineVariant = Color(0xFF3E4540)

private val DangerLight = Color(0xFFBA1A1A)
private val DangerDark = Color(0xFFFFB4AB)

private val LightColorScheme = lightColorScheme(
    primary = LightGreenPrimary,
    onPrimary = LightGreenOnPrimary,
    primaryContainer = LightGreenPrimaryContainer,
    onPrimaryContainer = LightGreenOnPrimaryContainer,
    secondary = DeepPineLight,
    onSecondary = Color.White,
    secondaryContainer = LightGreenPrimaryContainer,
    onSecondaryContainer = LightGreenOnPrimaryContainer,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = DangerLight,
    onError = Color.White
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkGreenPrimary,
    onPrimary = DarkGreenOnPrimary,
    primaryContainer = DarkGreenPrimaryContainer,
    onPrimaryContainer = DarkGreenOnPrimaryContainer,
    secondary = DeepPineDark,
    onSecondary = Color.Black,
    secondaryContainer = DarkGreenPrimaryContainer,
    onSecondaryContainer = DarkGreenOnPrimaryContainer,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    error = DangerDark,
    onError = Color.Black
)

private val RecordTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.2.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.15.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    )
)

@Composable
fun LifeRecordTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = RecordTypography,
        content = content
    )
}
