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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.cardpersonality.honor.ui.theme.CardPersonalityTheme
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Immutable navigation entry. Each transition renders from its own entry snapshot, so the outgoing
 * editor keeps showing the editor (with its target) while sliding out — it never flips to Home.
 */
sealed interface NavEntry {
    val key: String
    val depth: Int

    data class Home(val snapshot: HomeSnapshot?) : NavEntry {
        override val key: String = "__home__"
        override val depth: Int = 0
    }

    data class Editor(val target: EditorTarget) : NavEntry {
        override val key: String = "editor:${target.key.fileKey}"
        override val depth: Int = 1
    }

    data object About : NavEntry {
        override val key: String = "__about__"
        override val depth: Int = 1
    }
}

/**
 * Root composable of 药做卡面 (hosted by [com.cardpersonality.honor.MainActivity]).
 *
 * Round 31: navigation uses immutable [NavEntry] snapshots kept in a registry for the duration of
 * the transition. The outgoing page therefore renders its own entry (editor stays an editor) and
 * Home refresh/thumbnail work is paused until the slide finishes.
 */
@Composable
fun CardPersonalityApp() {
    CardPersonalityTheme {
        val ctx = LocalContext.current
        var homeSnapshot by remember { mutableStateOf<HomeSnapshot?>(null) }
        val homeListState = androidx.compose.foundation.lazy.rememberLazyListState()
        var navEntry by remember { mutableStateOf<NavEntry>(NavEntry.Home(null)) }
        val entries = remember { mutableStateMapOf<String, NavEntry>() }
        var transitionActive by remember { mutableStateOf(false) }

        // Keep every visited entry until its slide-out has finished (outgoing content needs it).
        LaunchedEffect(navEntry) { entries[navEntry.key] = navEntry }

        val navKey = navEntry.key
        LaunchedEffect(navKey) {
            transitionActive = true
            delay(400)
            transitionActive = false
            // Drop stale entries only after the transition has completed.
            entries.keys.filter { it != navKey }.forEach { entries.remove(it) }
        }

        fun go(entry: NavEntry) {
            transitionActive = true
            navEntry = entry
        }

        val slideSpec = tween<androidx.compose.ui.unit.IntOffset>(
            durationMillis = 300,
            easing = FastOutSlowInEasing,
        )
        val fadeSpec = tween<Float>(durationMillis = 300, easing = FastOutSlowInEasing)

        // Round 12: system back from About returns to the app's Home screen, not the launcher.
        BackHandler(enabled = navEntry is NavEntry.About) {
            go(NavEntry.Home(homeSnapshot))
        }

        AnimatedContent(
            targetState = navKey,
            transitionSpec = {
                // Forward: Home -> (Editor/About): new page from the right, old page to the left.
                // Back: current page slides out to the right, Home enters from the left. Only one
                // transition can run because targetState only changes when navEntry changes.
                val forward = initialState == "__home__" && targetState != "__home__"
                if (forward) {
                    (slideInHorizontally(slideSpec) { it } + fadeIn(fadeSpec)) togetherWith
                        (slideOutHorizontally(slideSpec) { -it / 4 } + fadeOut(fadeSpec))
                } else {
                    (slideInHorizontally(slideSpec) { -it / 4 } + fadeIn(fadeSpec)) togetherWith
                        (slideOutHorizontally(slideSpec) { it } + fadeOut(fadeSpec))
                }
            },
            label = "screen-slide",
        ) { currentKey ->
            val entry = entries[currentKey] ?: navEntry
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MiuixTheme.colorScheme.background),
            ) {
                when (entry) {
                    is NavEntry.Editor -> EditorScreen(
                        ctx = ctx,
                        target = entry.target,
                        onDone = {
                            if (navEntry is NavEntry.Editor) {
                                go(NavEntry.Home(homeSnapshot))
                            }
                        },
                    )
                    NavEntry.About -> AboutScreen(
                        ctx = ctx,
                        onBack = { go(NavEntry.Home(homeSnapshot)) },
                    )
                    is NavEntry.Home -> HomeScreen(
                        ctx = ctx,
                        onOpenEditor = { go(NavEntry.Editor(it)) },
                        onOpenAbout = { go(NavEntry.About) },
                        initialSnapshot = entry.snapshot ?: homeSnapshot,
                        onSnapshot = { snapshot ->
                            homeSnapshot = snapshot
                            entries["__home__"] = NavEntry.Home(snapshot)
                        },
                        listState = homeListState,
                        allowAutoRefresh = !transitionActive,
                    )
                }
            }
        }
    }
}

private val NavEntry.CompanionHolder: Unit get() = Unit

