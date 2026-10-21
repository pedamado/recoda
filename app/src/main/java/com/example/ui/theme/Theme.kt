package com.example.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val RamsMinimalColorScheme = lightColorScheme(
    primary = SolidBlack,
    onPrimary = PureWhite,
    primaryContainer = LightGray,
    onPrimaryContainer = SolidBlack,
    secondary = DarkGray,
    onSecondary = PureWhite,
    background = OffWhite,
    onBackground = SolidBlack,
    surface = PureWhite,
    onSurface = SolidBlack,
    surfaceVariant = LightGray,
    onSurfaceVariant = DarkGray,
    outline = GrayBorder,
    error = RamsRed,
    onError = PureWhite
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = RamsMinimalColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = colorScheme.background.toArgb()
                window.navigationBarColor = colorScheme.background.toArgb()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = true
                WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = true
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}
