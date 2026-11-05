package app.liferecord.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 视觉风格：安静深绿 / 米白纸质、正文优先、桌面端宽屏阅读耐看。
 * 遵循与 Android 端一致的语义色彩。
 */
val DeepPineLight = Color(0xFF1E3A2F)
val LightGreenPrimary = Color(0xFF264E3F)
val LightGreenOnPrimary = Color(0xFFFFFFFF)
val LightGreenPrimaryContainer = Color(0xFFD3E7DC)
val LightGreenOnPrimaryContainer = Color(0xFF072017)

val LightBackground = Color(0xFFF9F9F6) // 米白纸质
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFEBECE6)
val LightOnSurface = Color(0xFF1D201E)
val LightOnSurfaceVariant = Color(0xFF4C544F)
val LightOutline = Color(0xFF88928B)
val LightOutlineVariant = Color(0xFFD2D6D2)

val DeepPineDark = Color(0xFF85B5A1)
val DarkGreenPrimary = Color(0xFF90C2AD)
val DarkGreenOnPrimary = Color(0xFF003827)
val DarkGreenPrimaryContainer = Color(0xFF1B4F3D)
val DarkGreenOnPrimaryContainer = Color(0xFFB8EDD7)

val DarkBackground = Color(0xFF121513)
val DarkSurface = Color(0xFF191D1A)
val DarkSurfaceVariant = Color(0xFF252B27)
val DarkOnSurface = Color(0xFFE1E4DF)
val DarkOnSurfaceVariant = Color(0xFFA5ACA5)
val DarkOutline = Color(0xFF717972)
val DarkOutlineVariant = Color(0xFF3E4540)

val DangerLight = Color(0xFFBA1A1A)
val DangerDark = Color(0xFFFFB4AB)
val WarningGold = Color(0xFF7A5900)
val WarningGoldDark = Color(0xFFE5C158)

private val DesktopLightColorScheme = lightColorScheme(
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

private val DesktopDarkColorScheme = darkColorScheme(
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

private val DesktopTypography = Typography(
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
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.2.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.15.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.4.sp
    )
)

@Composable
fun LifeRecordDesktopTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DesktopDarkColorScheme else DesktopLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = DesktopTypography,
        content = content
    )
}

/**
 * 桌面端统一说明/状态提示面板
 */
@Composable
fun DesktopInfoPanel(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    isWarning: Boolean = false
) {
    val containerColor = when {
        isError -> MaterialTheme.colorScheme.error.copy(alpha = 0.08f)
        isWarning -> if (isSystemInDarkTheme()) WarningGoldDark.copy(alpha = 0.12f) else WarningGold.copy(alpha = 0.08f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val borderColor = when {
        isError -> MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
        isWarning -> if (isSystemInDarkTheme()) WarningGoldDark.copy(alpha = 0.4f) else WarningGold.copy(alpha = 0.4f)
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val titleColor = when {
        isError -> MaterialTheme.colorScheme.error
        isWarning -> if (isSystemInDarkTheme()) WarningGoldDark else WarningGold
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = titleColor,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 桌面端二次确认对话框
 */
@Composable
fun DesktopConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = false
) {
    val confirmTextColor = if (danger) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.defaultMinSize(minHeight = 36.dp),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = confirmTextColor
                )
            ) {
                Text(
                    text = confirmLabel,
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.defaultMinSize(minHeight = 36.dp)
            ) {
                Text(
                    text = "取消",
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp)
    )
}

/**
 * 桌面端属性键值对排版
 */
@Composable
fun DesktopTimeLabel(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.width(96.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * 桌面端语义状态徽标
 */
@Composable
fun DesktopStatusBadge(
    text: String,
    isWarning: Boolean = false,
    isError: Boolean = false,
    isSuccess: Boolean = false,
    modifier: Modifier = Modifier
) {
    val bgColor = when {
        isError -> MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
        isWarning -> if (isSystemInDarkTheme()) WarningGoldDark.copy(alpha = 0.16f) else WarningGold.copy(alpha = 0.12f)
        isSuccess -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = when {
        isError -> MaterialTheme.colorScheme.error
        isWarning -> if (isSystemInDarkTheme()) WarningGoldDark else WarningGold
        isSuccess -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = bgColor
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = textColor,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
