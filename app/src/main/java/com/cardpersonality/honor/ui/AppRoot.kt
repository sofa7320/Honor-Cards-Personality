package com.cardpersonality.honor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.cardpersonality.honor.ui.theme.CardPersonalityTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Root composable of 药做卡面 (hosted by [com.cardpersonality.honor.MainActivity]).
 *
 * State-based navigation: Home <-> Editor / About. Round 25: Miuix-style horizontal slide
 * transition (forward = new page from the right, back = old page to the right) with a light fade,
 * 300 ms, FastOutSlowIn. Layout/state handling is unchanged.
 */
@Composable
fun CardPersonalityApp() {
    CardPersonalityTheme {
        val ctx = LocalContext.current
        var editorTarget by remember { mutableStateOf<EditorTarget?>(null) }
        var showAbout by remember { mutableStateOf(false) }
        // Round 28: keep the Home snapshot + scroll position across editor navigation so returning
        // does not reload/rebuild the list or flash a loading state.
        var homeSnapshot by remember { mutableStateOf<HomeSnapshot?>(null) }
        val homeListState = androidx.compose.foundation.lazy.rememberLazyListState()
        val target = editorTarget

        // Round 12: system back from About returns to the app's Home screen, not the launcher.
        BackHandler(enabled = showAbout) { showAbout = false }

        data class Screen(val key: String, val depth: Int)
        val screen = when {
            target != null -> Screen("editor:${target.key.fileKey}", 1)
            showAbout -> Screen("__about__", 1)
            else -> Screen("__home__", 0)
        }
        val slideSpec = tween<androidx.compose.ui.unit.IntOffset>(
            durationMillis = 300,
            easing = FastOutSlowInEasing,
        )
        val fadeSpec = tween<Float>(durationMillis = 300, easing = FastOutSlowInEasing)

        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                val forward = targetState.depth >= initialState.depth
                if (forward) {
                    (slideInHorizontally(slideSpec) { it } + fadeIn(fadeSpec)) togetherWith
                        (slideOutHorizontally(slideSpec) { -it / 4 } + fadeOut(fadeSpec))
                } else {
                    (slideInHorizontally(slideSpec) { -it / 4 } + fadeIn(fadeSpec)) togetherWith
                        (slideOutHorizontally(slideSpec) { it } + fadeOut(fadeSpec))
                }
            },
            label = "screen-slide",
        ) { current ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MiuixTheme.colorScheme.background),
            ) {
                when {
                    current.key.startsWith("editor:") && target != null -> EditorScreen(
                        ctx = ctx,
                        target = target,
                        onDone = { editorTarget = null },
                    )
                    current.key == "__about__" -> AboutScreen(
                        ctx = ctx,
                        onBack = { showAbout = false },
                    )
                    else -> HomeScreen(
                        ctx = ctx,
                        onOpenEditor = { editorTarget = it },
                        onOpenAbout = { showAbout = true },
                        initialSnapshot = homeSnapshot,
                        onSnapshot = { homeSnapshot = it },
                        listState = homeListState,
                    )
                }
            }
        }
    }
}


