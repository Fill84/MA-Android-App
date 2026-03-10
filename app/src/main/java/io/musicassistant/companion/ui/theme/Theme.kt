package io.musicassistant.companion.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.musicassistant.companion.data.settings.ThemeMode

private val DarkColorScheme =
        darkColorScheme(
                primary = MaAccentBlue,
                onPrimary = Color.White,
                primaryContainer = MaAccentBlueDark,
                onPrimaryContainer = MaAccentBlueLight,
                secondary = BlueGrey80,
                tertiary = LightBlue80,
                background = MaDarkBackground,
                onBackground = MaTextPrimary,
                surface = MaDarkSurface,
                onSurface = MaTextPrimary,
                surfaceVariant = MaDarkSurfaceVariant,
                onSurfaceVariant = MaTextSecondary,
                surfaceContainer = MaDarkSurfaceContainer,
                surfaceContainerHigh = MaDarkSurfaceContainerHigh,
                outline = MaDarkOutline,
                error = MaDarkError,
                onError = Color.White,
        )

private val LightColorScheme =
        lightColorScheme(
                primary = MaLightPrimary,
                onPrimary = MaLightOnPrimary,
                primaryContainer = MaLightPrimaryContainer,
                onPrimaryContainer = MaLightOnPrimaryContainer,
                secondary = BlueGrey40,
                tertiary = LightBlue40,
                background = MaLightBackground,
                onBackground = MaLightTextPrimary,
                surface = MaLightSurface,
                onSurface = MaLightTextPrimary,
                surfaceVariant = MaLightSurfaceVariant,
                onSurfaceVariant = MaLightTextSecondary,
                surfaceContainer = MaLightSurfaceContainer,
                surfaceContainerHigh = MaLightSurfaceContainerHigh,
                outline = MaLightOutline,
                error = Color(0xFFDC2626),
                onError = Color.White,
        )

val MaShapes = Shapes(
        extraSmall = RoundedCornerShape(4.dp),
        small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(12.dp),
        large = RoundedCornerShape(16.dp),
        extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun MaCompanionTheme(
        themeMode: ThemeMode = ThemeMode.DARK,
        dynamicColor: Boolean = false,
        content: @Composable () -> Unit
) {
    val darkTheme =
            when (themeMode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }

    val colorScheme =
            when {
                dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    val context = LocalContext.current
                    if (darkTheme) dynamicDarkColorScheme(context)
                    else dynamicLightColorScheme(context)
                }
                darkTheme -> DarkColorScheme
                else -> LightColorScheme
            }

    MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = MaShapes,
            content = content,
    )
}
