package com.cardpersonality.honor.ui.util

import android.content.Context

/**
 * App-level editor preferences (NOT part of the card config / config.json).
 * Persisted in SharedPreferences.
 */
object EditorPrefs {
    private const val PREFS = "card_personality_editor"
    private const val KEY_SNAP = "auto_snap"
    private const val KEY_EXPORT_SCALE = "export_scale"

    /** Auto-snap of dragged stickers / the source image. Default: enabled. */
    fun isSnapEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SNAP, true)

    fun setSnapEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_SNAP, enabled).apply()
    }

    /** Round 25: export quality multiplier 1x..10x (0 = not chosen / adaptive default). */
    fun getExportScale(ctx: Context): Int =
        prefs(ctx).getInt(KEY_EXPORT_SCALE, 0).coerceIn(0, 10)

    fun setExportScale(ctx: Context, scale: Int) {
        prefs(ctx).edit().putInt(KEY_EXPORT_SCALE, scale.coerceIn(1, 10)).apply()
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}


