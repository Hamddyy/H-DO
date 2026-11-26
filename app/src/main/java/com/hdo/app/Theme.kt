package com.hdo.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

val LocalLang = compositionLocalOf { "ar" }

@Composable
fun t(key: String): String = Strings.get(key, LocalLang.current)

@Composable
fun tn(key: String, n: Any): String = Strings.get(key, LocalLang.current).replace("{n}", n.toString())

val ACCENTS = listOf(0xFF0EA5E9, 0xFF8B5CF6, 0xFF10B981, 0xFFF97316, 0xFFEC4899, 0xFFEAB308).map { it.toInt() }

@Composable
fun HdoTheme(content: @Composable () -> Unit) {
    val s by Store.settings.collectAsState()
    val dark = when (s.theme) { "dark", "black" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val black = s.theme == "black"
    val acc = Color(s.accent)
    val onAcc = if (acc.luminance() > 0.55f) Color(0xFF05122B) else Color.White
    val scheme = if (dark) darkColorScheme(
        primary = acc, onPrimary = onAcc,
        background = if (black) Color.Black else Color(0xFF070F26), onBackground = Color(0xFFEAF0FF),
        surface = if (black) Color(0xFF0B0B0B) else Color(0xFF0F1D44), onSurface = Color(0xFFEAF0FF),
        surfaceVariant = if (black) Color(0xFF181818) else Color(0xFF14275A), onSurfaceVariant = Color(0xFF9AAAD2),
        outline = if (black) Color(0xFF2A2A2A) else Color(0xFF1F3470), outlineVariant = if (black) Color(0xFF2A2A2A) else Color(0xFF1F3470),
        secondaryContainer = acc.copy(alpha = 0.22f), onSecondaryContainer = acc, error = Color(0xFFFF6B6B),
    ) else lightColorScheme(
        primary = acc, onPrimary = onAcc, background = Color(0xFFF4F6FB), onBackground = Color(0xFF0F1B3A),
        surface = Color.White, onSurface = Color(0xFF0F1B3A), surfaceVariant = Color(0xFFEEF1F8), onSurfaceVariant = Color(0xFF667394),
        outline = Color(0xFFDFE5F1), outlineVariant = Color(0xFFDFE5F1), secondaryContainer = acc.copy(alpha = 0.16f),
        onSecondaryContainer = acc, error = Color(0xFFDC2626),
    )
    val base = LocalDensity.current
    CompositionLocalProvider(
        LocalLang provides s.lang,
        LocalLayoutDirection provides (if (s.lang == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr),
        LocalDensity provides Density(base.density, base.fontScale * s.fontScale),
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
