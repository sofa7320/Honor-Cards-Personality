package com.cardpersonality.honor.hook

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.ViewTreeObserver
import android.widget.ImageView
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Captures the wallet's CURRENT card face as a JPEG snapshot for the panel.
 *
 * Why: some card faces have no remote URL at all (classic access-card bundled drawables) and the
 * panel still needs to display "current wallet face".
 *
 * Threading (ANR fix v14): the drawable is captured on the main thread (must be the UI thread)
 * but ALL provider IPC / scaling / JPEG encoding happens on a private background executor — the
 * wallet main thread never waits on our ContentProvider.
 *
 * Guarantees: at most one capture attempt per (card key, wallet process); nothing is captured when
 * a custom face is configured (never mistake our own image for the original); failures only log;
 * JPEG <= 512 KB (provider enforces too); type whitelist only.
 */
object FaceCapture {
    private const val MAX_STORED_BYTES = 512 * 1024
    private const val MAX_LONG_SIDE = 640
    private const val MAX_ATTEMPTS = 30
    private const val JPEG_QUALITY = 85

    private val TYPE_WHITELIST = setOf(
        CardTypesBridge.TRANSIT, CardTypesBridge.BANK, CardTypesBridge.ACCESS,
        CardTypesBridge.CAMPUS, CardTypesBridge.CARKEY, CardTypesBridge.EBIKE,
    )

    /** Guards against a second capture of the same key in this wallet process. */
    private val captured = ConcurrentHashMap<String, Long>()

    private val pendingListeners = ConcurrentHashMap<ImageView, ViewTreeObserver.OnPreDrawListener>()

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "cardpersonality-face-capture").apply { isDaemon = true }
    }

    /** Card type string constants (keep the hook free of UI dependencies). */
    private object CardTypesBridge {
        const val TRANSIT = "transit"
        const val BANK = "bank"
        const val ACCESS = "access"
        const val CAMPUS = "campus"
        const val CARKEY = "carkey"
        const val EBIKE = "ebike"
    }

    /**
     * Waits for one successful frame draw of [view], then captures its drawable.
     * Cheap: registers a pre-draw listener only; no provider IPC on the main thread.
     */
    fun schedule(view: ImageView, identity: CardIdentity) {
        try {
            if (identity.type !in TYPE_WHITELIST) return
            val key = identity.fileKey
            if (captured.containsKey(key)) return
            if (pendingListeners.containsKey(view)) return
            val listener = object : ViewTreeObserver.OnPreDrawListener {
                private var attempts = 0
                override fun onPreDraw(): Boolean {
                    attempts++
                    if (!view.isAttachedToWindow || attempts > MAX_ATTEMPTS || captured.containsKey(key)) {
                        cleanup(view)
                        return true
                    }
                    // Draw happens right after onPreDraw returns; grab the bitmap on the next turn.
                    view.post { captureOnMain(view, identity) }
                    return true
                }
            }
            view.viewTreeObserver.addOnPreDrawListener(listener)
            pendingListeners[view] = listener
            HLog.limWithSummary("capture_sched", 6) { "original-face capture scheduled $key" }
        } catch (t: Throwable) {
            HLog.lim("capture_sched_fail", 3) { "capture schedule failed: ${t.javaClass.simpleName}: ${t.message}" }
        }
    }

    /** Main thread: copy the drawable bitmap, then hand processing to the worker. */
    private fun captureOnMain(view: ImageView, identity: CardIdentity) {
        try {
            if (captured.containsKey(identity.fileKey)) {
                cleanup(view)
                return
            }
            val drawable = view.drawable ?: return // not ready yet; the pre-draw listener retries
            val bitmap = drawableToBitmap(drawable) ?: return
            captured[identity.fileKey] = System.currentTimeMillis()
            cleanup(view)
            executor.execute { processCapture(identity, bitmap) }
        } catch (t: Throwable) {
            HLog.lim("capture_err", 3) { "capture (main) failed: ${t.javaClass.simpleName}: ${t.message}" }
        }
    }

    /** Background: config check + scaling + JPEG + provider save. Never touches main thread. */
    private fun processCapture(identity: CardIdentity, bitmap: Bitmap) {
        var scaled: Bitmap? = null
        try {
            // Refresh config on the worker (IPC allowed here) so we never capture our own face.
            ProviderClient.config(force = true)
            if (ProviderClient.isConfigured(identity.type, identity.id)) {
                HLog.limWithSummary("capture_skip_cfg", 4) {
                    "original capture skipped (custom face configured) ${identity.fileKey}"
                }
                return
            }
            if (!ProviderClient.faceSnapshotNeeded(identity.type, identity.id)) return
            scaled = scaleDown(bitmap)
            val bytes = encodeJpeg(scaled)
            if (bytes == null || bytes.size > MAX_STORED_BYTES) {
                HLog.limWithSummary("capture_skip", 3) {
                    "original snapshot skipped for ${identity.fileKey} (${bytes?.size ?: 0} bytes)"
                }
                return
            }
            val ok = ProviderClient.saveOriginalFace(identity.type, identity.id, bytes)
            HLog.limWithSummary("capture_ok", 8) {
                "original-face snapshot ${identity.fileKey} bytes=${bytes.size} ok=$ok"
            }
        } catch (t: Throwable) {
            HLog.lim("capture_err", 3) { "capture (worker) failed: ${t.javaClass.simpleName}: ${t.message}" }
        } finally {
            if (scaled != null && scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun cleanup(view: ImageView) {
        val listener = pendingListeners.remove(view) ?: return
        try {
            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnPreDrawListener(listener)
        } catch (_: Throwable) {
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap? {
        try {
            if (drawable is BitmapDrawable) {
                val bmp = drawable.bitmap
                if (bmp != null && !bmp.isRecycled && bmp.width > 0 && bmp.height > 0) {
                    return Bitmap.createBitmap(bmp)
                }
                return null
            }
            val w = drawable.intrinsicWidth
            val h = drawable.intrinsicHeight
            if (w <= 0 || h <= 0) return null
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.drawColor(Color.WHITE)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(canvas)
            return out
        } catch (_: Throwable) {
            return null
        }
    }

    private fun scaleDown(src: Bitmap): Bitmap {
        val longSide = maxOf(src.width, src.height)
        if (longSide <= MAX_LONG_SIDE) return src
        val factor = MAX_LONG_SIDE.toFloat() / longSide.toFloat()
        val w = maxOf(1, (src.width * factor).toInt())
        val h = maxOf(1, (src.height * factor).toInt())
        return try {
            Bitmap.createScaledBitmap(src, w, h, true)
        } catch (_: Throwable) {
            src
        }
    }

    private fun encodeJpeg(bitmap: Bitmap): ByteArray? {
        return try {
            val out = ByteArrayOutputStream()
            val flat = if (bitmap.hasAlpha()) {
                val bg = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bg)
                canvas.drawColor(Color.WHITE)
                canvas.drawBitmap(bitmap, 0f, 0f, null)
                bg
            } else {
                bitmap
            }
            flat.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            if (flat !== bitmap) flat.recycle()
            out.toByteArray()
        } catch (_: Throwable) {
            null
        }
    }
}
