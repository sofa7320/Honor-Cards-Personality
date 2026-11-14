package com.cardpersonality.honor.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * Miuix theme wrapper for 卡面工坊.
 *
 * Uses [ColorSchemeMode.System] so the panel follows the system light/dark mode exactly like a
 * native HyperOS settings app; the palette is Miuix's own (HyperOS blue).
 */
@Composable
fun CardPersonalityTheme(content: @Composable () -> Unit) {
    val controller = remember { ThemeController(ColorSchemeMode.System) }
    MiuixTheme(controller = controller) {
        content()
    }
}
