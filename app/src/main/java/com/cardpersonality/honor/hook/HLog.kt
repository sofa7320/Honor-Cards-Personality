package com.cardpersonality.honor.hook

import android.util.Log
import de.robv.android.xposed.XposedBridge

/**
 * Logging helper for the wallet process.
 *
 * Everything is best-effort: a logging failure must never take the wallet down.
 * Lines are written both to logcat (tag CARD_PERSONALITY) and to the LSPosed log
 * (XposedBridge.log) so the verifier can read them without root filtering.
 */
object HLog {
    const val TAG = "CARD_PERSONALITY"
    const val ALIVE = "CARD_PERSONALITY_ALIVE"

    private val counters = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun i(msg: String) {
        try {
            Log.i(TAG, msg)
        } catch (_: Throwable) {
        }
        try {
            XposedBridge.log("$TAG $msg")
        } catch (_: Throwable) {
        }
    }

    fun w(msg: String, t: Throwable? = null) {
        try {
            if (t != null) Log.w(TAG, msg, t) else Log.w(TAG, msg)
        } catch (_: Throwable) {
        }
        try {
            if (t != null) XposedBridge.log("$TAG WARN $msg :: $t") else XposedBridge.log("$TAG WARN $msg")
        } catch (_: Throwable) {
        }
    }

    fun e(msg: String, t: Throwable? = null) {
        try {
            if (t != null) Log.e(TAG, msg, t) else Log.e(TAG, msg)
        } catch (_: Throwable) {
        }
        try {
            if (t != null) XposedBridge.log("$TAG ERROR $msg :: $t") else XposedBridge.log("$TAG ERROR $msg")
        } catch (_: Throwable) {
        }
    }

    /** Emits at most [max] lines per [key] to keep logcat readable on hot paths. */
    fun lim(key: String, max: Int = 20, msg: () -> String) {
        val n = counters.merge(key, 1) { a, _ -> a + 1 } ?: 1
        if (n <= max) i(msg())
    }

    /** Emits the first [max] lines per [key], then one summary line when suppressed. */
    fun limWithSummary(key: String, max: Int = 20, msg: () -> String) {
        val n = counters.merge(key, 1) { a, _ -> a + 1 } ?: 1
        if (n <= max) {
            i(msg())
        } else if (n == max + 1) {
            i("$key: further lines suppressed")
        }
    }
}
