package com.cardpersonality.honor.ui.util

import android.content.Context
import com.cardpersonality.honor.data.FaceRepository
import java.util.concurrent.ConcurrentHashMap

/**
 * Round 23: "bidirectional" wallet preview refresh.
 *
 * When the user customizes a card face inside the wallet itself (e.g. an Honor access card using the
 * wallet's native customizer), the module's cached "original face" must be invalidated so the panel
 * preview/editor background picks up the wallet's current face. Both the editor (on open) and the
 * Home screen (on resume) call this; a 2 s per-card debounce keeps repeated lifecycle events from
 * spamming the hook capture path.
 */
object OriginalSnapshotRefresh {

    private const val DEBOUNCE_MS = 2_000L
    private val lastRefresh = ConcurrentHashMap<String, Long>()

    /**
     * Invalidates the cached wallet-original snapshot for one card, at most once every 2 s per card.
     * Safe to call from the main thread (the repository call is cheap: provider/bridge broadcasts).
     */
    fun refreshIfNeeded(
        ctx: Context,
        type: String,
        id: String,
        debounceMs: Long = DEBOUNCE_MS,
    ) {
        if (type.isEmpty() || id.isEmpty()) return
        val key = "$type:$id"
        val now = System.currentTimeMillis()
        val previous = lastRefresh[key] ?: 0L
        if (now - previous < debounceMs) return
        lastRefresh[key] = now
        runCatching { FaceRepository.refreshOriginalSnapshots(ctx, type, id) }
    }

    /** Refreshes [cards] that are NOT customized by the panel (wallet-native faces stay live). */
    fun refreshUncustomized(ctx: Context, cards: List<Pair<String, String>>) {
        for ((type, id) in cards) refreshIfNeeded(ctx, type, id)
    }
}
