package com.vasmarfas.UniversalAmbientLight.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = AmbientCyan80,
    secondary = AmbientBlueGrey80,
    tertiary = AmbientAmber80,
    background = AmbientDarkBackground,
    surface = AmbientDarkSurface
)

private val LightColorScheme = lightColorScheme(
    primary = AmbientCyan40,
    secondary = AmbientBlueGrey40,
    tertiary = AmbientAmber40
)

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Динамические цвета доступны с Android 12
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // view.context может оказаться ContextWrapper (например, во вложенных compose-хостах):
            // идём по цепочке обёрток, а не падаем на жёстком приведении типа.
            val activity = generateSequence<android.content.Context>(view.context) {
                (it as? android.content.ContextWrapper)?.baseContext
            }.firstOrNull { it is Activity } as? Activity ?: return@SideEffect
            val window = activity.window
            val insetsController = WindowCompat.getInsetsController(window, view)

            // setStatusBarColor убран в Android 15 (API 35).
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                @Suppress("DEPRECATION")
                window.statusBarColor = Color.Transparent.toArgb()
            }

            insetsController.isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
