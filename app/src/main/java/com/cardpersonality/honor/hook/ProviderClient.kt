package com.cardpersonality.honor.hook

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.cardpersonality.honor.data.CardKey
import com.cardpersonality.honor.data.Hashing
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hook-side client for FaceProvider (runs inside the wallet process).
 *
 * Channel: ContentResolver.call(content://com.cardpersonality.honor.face, ...)
 *
 * Threading contract (ANR fix v14):
 *  - Provider IPC is NEVER performed on the caller's thread unless `config(force = true)` is
 *    explicitly requested, and forced refreshes are only issued from the internal background
 *    executor (install prefetch, config-change refresh, original-face capture).
 *  - Normal callers (wallet main thread: bind hooks, face-source getter after-hooks) only read the
 *    in-memory snapshot: `config(false)` returns the cached snapshot (possibly null) and schedules
 *    an async prefetch. A null snapshot simply means "no custom face resolved yet" (fail-open).
 *  - Any IPC failure (including IllegalArgumentException "Unknown URL") is swallowed, logged at
 *    most once per 30 s, and puts the provider into a 30 s backoff window with zero further IPC.
 *
 * Resolution mirrors FaceRepository.effective():
 *   1. exact fileKey "<type>:<sanitizedIdentity>" with hasCustomFace == true
 *   2. type default "type:<type>" with hasCustomFace == true
 * Image URI: content://com.cardpersonality.honor.face/card/<sha256hex(fileKey)>.jpg?v=<cardVersion>
 *   -> served by FaceProvider from <filesDir>/exports/<sha256hex(fileKey)>.jpg
 */
object ProviderClient {
    const val AUTHORITY = "com.cardpersonality.honor.face"
    private const val BASE_URI = "content://$AUTHORITY"
    private const val CALL_CONFIG = "config"
    private const val CALL_REPORT = "report"
    private const val CALL_SAVE_ORIGINAL = "saveOriginalFace"
    private const val CALL_ORIGINAL_EXISTS = "originalExists"
    private const val EXTRA_JSON = "json"
    private const val EXTRA_VERSION = "version"
    private const val EXTRA_TYPE = "type"
    private const val EXTRA_ID = "id"
    private const val EXTRA_DATA = "data"
    private const val EXTRA_EXISTS = "exists"
    private const val EXTRA_OK = "ok"

    private const val TTL_MS = 5_000L

    /** After any provider IPC failure, no further IPC for this long. */
    private const val FAILURE_BACKOFF_MS = 30_000L

    /** Short retry window for transient cold-start errors (provider app in stopped state). */
    private const val TRANSIENT_BACKOFF_MS = 1_200L

    /** Last good config JSON cached in the wallet process cache (cold-start fallback). */
    private const val CONFIG_CACHE_FILE = "cardpersonality_config_cache.json"

    /** One background worker for all provider IPC scheduled from hook code. */
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "cardpersonality-provider-client").apply { isDaemon = true }
    }

    private val prefetchScheduled = AtomicBoolean(false)

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Callbacks fired on the MAIN thread when a new config snapshot arrives (cold-start fix). */
    private val configListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** Consecutive transient provider failures ("Unknown authority" while app is stopped). */
    @Volatile private var transientFailures = 0

    /** One resolved custom face for display. */
    data class ResolvedFace(val fileKey: String, val version: Int) {
        val sha256: String get() = Hashing.sha256Hex(fileKey)
        fun contentUri(): Uri = Uri.parse("$BASE_URI/card/$sha256.jpg?v=$version")
    }

    class Snapshot(
        val globalVersion: Int,
        /** fileKey -> per-card version, only entries with hasCustomFace == true. */
        val cards: Map<String, Int>,
        /** type -> version, only type defaults with hasCustomFace == true. */
        val types: Map<String, Int>,
        /** fileKey -> app-element view ids to hide (KEEP/HIDE feature). */
        val hidden: Map<String, List<String>> = emptyMap(),
        /** type -> app-element view ids to hide (per-type defaults). */
        val typeHidden: Map<String, List<String>> = emptyMap(),
    )

    @Volatile private var snapshot: Snapshot? = null
    @Volatile private var snapshotAt = 0L
    @Volatile private var unavailableUntil = 0L
    @Volatile private var lastFailure: String? = null
    @Volatile private var lastFailureLogAt = 0L
    @Volatile private var loggedOkVersion = Int.MIN_VALUE

    /** App context inside the wallet process (reflection; no Xposed helper needed). */
    fun appContext(): Context? {
        return try {
            val activityThread = Class.forName("android.app.ActivityThread")
            val app = activityThread.getMethod("currentApplication").invoke(null)
            app as? Context
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Schedules a background config refresh; never blocks, never probes twice concurrently.
     */
    fun prefetchAsync() {
        if (!prefetchScheduled.compareAndSet(false, true)) return
        try {
            executor.execute {
                try {
                    config(force = true)
                } catch (_: Throwable) {
                } finally {
                    prefetchScheduled.set(false)
                }
            }
        } catch (_: Throwable) {
            prefetchScheduled.set(false)
        }
    }

    /** Registers a main-thread callback invoked whenever a NEW config snapshot arrives. */
    fun addConfigListener(listener: () -> Unit) {
        try {
            configListeners.add(listener)
        } catch (_: Throwable) {
        }
    }

    private fun notifyConfigListeners() {
        for (listener in configListeners) {
            try {
                mainHandler.post {
                    try {
                        listener()
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }
    /**
     * Forced background config refresh followed by [onDone] on the MAIN thread.
     * Used by config-change handling so no provider IPC ever runs on the wallet main thread.
     * Never throws.
     */
    fun refreshAsync(onDone: (() -> Unit)? = null) {
        try {
            executor.execute {
                try {
                    config(force = true)
                } catch (_: Throwable) {
                }
                if (onDone != null) {
                    try {
                        mainHandler.post {
                            try {
                                onDone()
                            } catch (_: Throwable) {
                            }
                        }
                    } catch (_: Throwable) {
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * Cached config snapshot.
     *
     * `force = false` (default) NEVER performs provider IPC on the calling thread: it returns the
     * current snapshot (or null) and schedules an async refresh when stale. `force = true` performs
     * the IPC and is only used from the background executor.
     */
    fun config(force: Boolean = false): Snapshot? {
        val now = System.currentTimeMillis()
        val cached = snapshot

        // Failure backoff: stay silent (no IPC) for a while after any provider error.
        if (now < unavailableUntil) return cached

        if (!force) {
            if (cached != null && now - snapshotAt < TTL_MS) return cached
            prefetchAsync()
            return cached
        }

        val ctx = appContext()
        if (ctx == null) {
            onFailure("no app context in wallet process")
            return cached
        }
        val bundle = try {
            ctx.contentResolver.call(Uri.parse(BASE_URI), CALL_CONFIG, null, null)
        } catch (t: Throwable) {
            onFailure("call(config) threw: ${t.javaClass.simpleName}: ${t.message}")
            return loadCachedConfig(ctx, cached)
        }
        if (bundle == null) {
            onFailure("call(config) returned null bundle")
            return loadCachedConfig(ctx, cached)
        }
        val json = bundle.getString(EXTRA_JSON)
        if (json.isNullOrEmpty()) {
            onFailure("call(config) returned empty json")
            return loadCachedConfig(ctx, cached)
        }
        val parsed = parse(json, bundle.getInt(EXTRA_VERSION, 0))
        if (parsed == null) {
            onFailure("call(config) json parse failed")
            return loadCachedConfig(ctx, cached)
        }
        val previousVersion = snapshot?.globalVersion
        snapshot = parsed
        snapshotAt = now
        lastFailure = null
        unavailableUntil = 0L
        transientFailures = 0
        saveCachedConfig(ctx, json)
        if (previousVersion == null || previousVersion != parsed.globalVersion) {
            notifyConfigListeners()
        }
        if (loggedOkVersion != parsed.globalVersion) {
            loggedOkVersion = parsed.globalVersion
            HLog.i("PROVIDER_OK config version=${parsed.globalVersion} " +
                "cards=${parsed.cards.size} types=${parsed.types.size}")
            HLog.i("PROVIDER_KEYS ${parsed.cards.keys.joinToString(",")} " +
                "types=${parsed.types.keys.joinToString(",")}")
        }
        return parsed
    }

    /**
     * Resolve a custom face for a card: exact identity first, then the per-type default.
     * Returns null when nothing is configured (caller must keep the original face).
     * Cached-only: never performs provider IPC on the calling thread.
     */
    fun resolve(type: String, identity: String?): ResolvedFace? {
        val snap = config() ?: return null
        if (identity != null && identity.isNotEmpty()) {
            val fileKey = CardKey(type, identity).fileKey
            snap.cards[fileKey]?.let { return ResolvedFace(fileKey, it) }
        }
        // "type:<type>" is the per-type fallback storage key.
        val typeKey = CardKey.typeDefault(type).fileKey
        snap.types[type]?.let { return ResolvedFace(typeKey, it) }
        return null
    }

    /** True when a custom face exists for this card (used by getter hooks). */
    fun isConfigured(type: String, identity: String?): Boolean = resolve(type, identity) != null

    /**
     * App-element view ids the user chose to HIDE for this card (exact key ∪ type default),
     * see work/re/cards/FINDINGS.md §12. KEEP is the default (empty list = keep everything).
     */
    fun hiddenElements(type: String, identity: String?): List<String> {
        val snap = config() ?: return emptyList()
        val out = ArrayList<String>()
        if (identity != null && identity.isNotEmpty()) {
            snap.hidden[CardKey(type, identity).fileKey]?.let { out.addAll(it) }
        }
        snap.typeHidden[type]?.let { out.addAll(it) }
        return out.distinct()
    }

    /** Re-resolves a stored fileKey ("type:identity" / "type:<type>") after a config change. */
    fun resolveFileKey(fileKey: String): ResolvedFace? {
        val key = CardKey.fromFileKey(fileKey) ?: return null
        return resolve(key.type, key.id)
    }

    /** Reports detected cards (JSON array of CardInfo) to the app. MUST run on a worker thread. */
    fun report(cardsJson: String): Boolean {
        if (System.currentTimeMillis() < unavailableUntil) return false
        val ctx = appContext() ?: return false
        return try {
            val extras = Bundle().apply { putString(EXTRA_JSON, cardsJson) }
            val result = ctx.contentResolver.call(Uri.parse(BASE_URI), CALL_REPORT, null, extras)
            val ok = result?.getBoolean(EXTRA_OK, false) == true
            HLog.lim("report_ok", 5) { "report: ok=$ok cards=${cardsJson.take(200)}" }
            ok
        } catch (t: Throwable) {
            onFailure("report threw: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /** Fire-and-forget report from hot hook paths (main thread safe). */
    fun reportAsync(cardsJson: String) {
        try {
            executor.execute {
                try {
                    report(cardsJson)
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }

    /** True when the app still needs an original-face snapshot for this card (round 2 #1). */
    fun faceSnapshotNeeded(type: String, identity: String): Boolean {
        if (identity.isEmpty()) return false
        if (System.currentTimeMillis() < unavailableUntil) return false
        val ctx = appContext() ?: return false
        return try {
            val extras = Bundle().apply {
                putString(EXTRA_TYPE, type)
                putString(EXTRA_ID, identity)
            }
            val result = ctx.contentResolver.call(Uri.parse(BASE_URI), CALL_ORIGINAL_EXISTS, null, extras)
            result != null && !result.getBoolean(EXTRA_EXISTS, false)
        } catch (t: Throwable) {
            onFailure("originalExists threw: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /** Sends a captured original-face JPEG to the app (FaceProvider persists it idempotently). */
    fun saveOriginalFace(type: String, identity: String, jpeg: ByteArray): Boolean {
        if (identity.isEmpty() || jpeg.isEmpty()) return false
        if (System.currentTimeMillis() < unavailableUntil) return false
        val ctx = appContext() ?: return false
        return try {
            val extras = Bundle().apply {
                putString(EXTRA_TYPE, type)
                putString(EXTRA_ID, identity)
                putByteArray(EXTRA_DATA, jpeg)
            }
            val result = ctx.contentResolver.call(Uri.parse(BASE_URI), CALL_SAVE_ORIGINAL, null, extras)
            result?.getBoolean(EXTRA_OK, false) == true
        } catch (t: Throwable) {
            onFailure("saveOriginalFace threw: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /**
     * Cold-start fallback: read the last good config JSON from the wallet process cache when the
     * provider is unavailable (e.g. panel app force-stopped / still starting). This lets the
     * double-press-power quick card show the custom face immediately after a cold start.
     */
    private fun loadCachedConfig(ctx: Context, cached: Snapshot?): Snapshot? {
        if (cached != null) return cached
        return try {
            val file = File(ctx.cacheDir, CONFIG_CACHE_FILE)
            if (!file.isFile || file.length() <= 0L) return null
            val json = file.readText(Charsets.UTF_8)
            val parsed = parse(json, 0) ?: return null
            snapshot = parsed
            snapshotAt = System.currentTimeMillis()
            HLog.i("PROVIDER_CACHE config from wallet cache (provider unavailable) v=${parsed.globalVersion}")
            parsed
        } catch (_: Throwable) {
            null
        }
    }

    private fun saveCachedConfig(ctx: Context, json: String) {
        try {
            val file = File(ctx.cacheDir, CONFIG_CACHE_FILE)
            file.parentFile?.mkdirs()
            val tmp = File(file.absolutePath + ".tmp")
            tmp.writeText(json, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (_: Throwable) {
        }
    }
    /**
     * Records a provider IPC failure: enters a 30 s no-IPC backoff, logs at most once per window.
     * Never throws.
     */
    private fun onFailure(reason: String) {
        val now = System.currentTimeMillis()
        lastFailure = reason
        snapshotAt = now
        // Cold-start: the panel app may be in Android stopped state ("Unknown authority") until it
        // is launched; that is transient, so retry soon instead of waiting 30 s.
        val transient = reason.contains("Unknown authority", ignoreCase = true) ||
            reason.contains("Unknown URL", ignoreCase = true)
        val backoff = if (transient) TRANSIENT_BACKOFF_MS else FAILURE_BACKOFF_MS
        unavailableUntil = now + backoff
        if (now - lastFailureLogAt >= 5_000L) {
            lastFailureLogAt = now
            HLog.w("PROVIDER_FAIL $reason (no provider IPC for ${backoff / 1000.0}s)")
        }
        if (transient && transientFailures < 20) {
            transientFailures++
            try {
                mainHandler.postDelayed({
                    try {
                        prefetchAsync()
                    } catch (_: Throwable) {
                    }
                }, backoff + 100L)
            } catch (_: Throwable) {
            }
        }
    }

    fun lastFailure(): String? = lastFailure

    private fun parse(json: String, bundleVersion: Int): Snapshot? {
        return try {
            val root = JSONObject(json)
            val globalVersion = if (root.has("version")) root.optInt("version", bundleVersion) else bundleVersion
            val cards = LinkedHashMap<String, Int>()
            val hidden = LinkedHashMap<String, List<String>>()
            root.optJSONObject("cards")?.let { cardsObj ->
                val keys = cardsObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val cfg = cardsObj.optJSONObject(key) ?: continue
                    val hide = cfg.optJSONArray("hiddenAppElements").toStringListSafe()
                    if (hide.isNotEmpty()) hidden[key] = hide
                    if (!cfg.optBoolean("hasCustomFace", false)) continue
                    cards[key] = cfg.optInt("version", 0)
                }
            }
            val types = LinkedHashMap<String, Int>()
            val typeHidden = LinkedHashMap<String, List<String>>()
            root.optJSONObject("types")?.let { typesObj ->
                val keys = typesObj.keys()
                while (keys.hasNext()) {
                    val type = keys.next()
                    val cfg = typesObj.optJSONObject(type) ?: continue
                    val hide = cfg.optJSONArray("hiddenAppElements").toStringListSafe()
                    if (hide.isNotEmpty()) typeHidden[type] = hide
                    if (!cfg.optBoolean("hasCustomFace", false)) continue
                    types[type] = cfg.optInt("version", 0)
                }
            }
            Snapshot(globalVersion, cards, types, hidden, typeHidden)
        } catch (t: Throwable) {
            null
        }
    }

    private fun org.json.JSONArray?.toStringListSafe(): List<String> {
        if (this == null) return emptyList()
        val out = ArrayList<String>(length())
        for (i in 0 until length()) {
            val value = optString(i, "")
            if (value.isNotEmpty()) out.add(value)
        }
        return out
    }
}
