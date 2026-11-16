package com.cardpersonality.honor.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import com.cardpersonality.honor.ui.theme.CardPersonalityTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Root composable of 荣耀卡面工坊 (hosted by [com.cardpersonality.honor.MainActivity]).
 *
 * State-based navigation: Home <-> Editor. The transition is a 140ms fade of the incoming screen
 * only — no translation and no transparent frames (both screens are fully opaque), which avoids the
 * flicker that a slide/translation of scrolling content used to cause.
 */
@Composable
fun CardPersonalityApp() {
    CardPersonalityTheme {
        val ctx = LocalContext.current
        var editorTarget by remember { mutableStateOf<EditorTarget?>(null) }
        var showAbout by remember { mutableStateOf(false) }
        val target = editorTarget

        val enter = remember { Animatable(1f) }
        // Skip the entrance animation on the very first frame so the initial screen never flashes.
        var firstFrame by remember { mutableStateOf(true) }
        val navigationKey = when {
            target != null -> target.key.fileKey
            showAbout -> "__about__"
            else -> "__home__"
        }
        LaunchedEffect(navigationKey) {
            if (firstFrame) {
                firstFrame = false
                return@LaunchedEffect
            }
            enter.snapTo(0f)
            enter.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.background),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MiuixTheme.colorScheme.background)
                    .graphicsLayer { alpha = enter.value },
            ) {
                when {
                    target != null -> EditorScreen(
                        ctx = ctx,
                        target = target,
                        onDone = { editorTarget = null },
                    )
                    showAbout -> AboutScreen(ctx = ctx, onBack = { showAbout = false })
                    else -> HomeScreen(
                        ctx = ctx,
                        onOpenEditor = { editorTarget = it },
                        onOpenAbout = { showAbout = true },
                    )
                }
            }
        }
    }
}

