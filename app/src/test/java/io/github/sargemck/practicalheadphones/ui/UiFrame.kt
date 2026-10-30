package io.github.sargemck.practicalheadphones.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/** Light, dark and the largest font scale (2.0 on Android 14+). */
enum class Variant(val dark: Boolean, val fontScale: Float) {
    LIGHT(false, 1f),
    DARK(true, 1f),
    FONT2(false, 2f),
}

/** Fixed (non-dynamic) colours, so screenshots don't depend on a wallpaper. */
@Composable
fun UiFrame(variant: Variant, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, variant.fontScale)) {
        AppTheme(dark = variant.dark, dynamic = false) {
            Surface(color = MaterialTheme.colorScheme.background, content = content)
        }
    }
}
