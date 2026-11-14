package com.cardpersonality.honor.ui.util

import android.content.Context

/**
 * App-level editor preferences (NOT part of the card config / config.json).
 * Persisted in SharedPreferences.
 */
object EditorPrefs {
    private const val PREFS = "card_personality_editor"
    private const val KEY_SNAP = "auto_snap"

    /** Auto-snap of dragged stickers / the source image. Default: enabled. */
    fun isSnapEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SNAP, true)

    fun setSnapEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_SNAP, enabled).apply()
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
