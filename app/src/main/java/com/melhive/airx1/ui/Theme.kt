package com.melhive.airx1.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

val AirAccent = Color(0xFF4FD1C5)
val AirGood = Color(0xFF3FB950)
val AirBad = Color(0xFFF85149)

private val DarkColors = darkColorScheme(
    primary = AirAccent,
    onPrimary = Color(0xFF00201D),
    background = Color(0xFF0E1116),
    surface = Color(0xFF0E1116),
    surfaceVariant = Color(0xFF1B2028),
)
private val LightColors = lightColorScheme(
    primary = Color(0xFF00897B),
)

@Composable
fun AirX1Theme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
