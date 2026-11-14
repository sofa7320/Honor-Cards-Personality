package com.cardpersonality.honor.hook

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Loads/decode card-face JPEGs from FaceProvider inside the wallet process.
 *
 * - one shared in-process bitmap cache keyed by "<fileKey>@<version>" (version bumps invalidate),
 * - single background thread for content:// reads,
 * - callbacks always on the main thread.
 */
object FaceImageLoader {
    private val cache = ConcurrentHashMap<String, Bitmap>()

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "cardpersonality-face-loader").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    fun cacheKey(face: ProviderClient.ResolvedFace): String = "${face.fileKey}@${face.version}"

    /** Synchronous cache peek (main thread safe); null when not decoded yet. */
    fun peek(face: ProviderClient.ResolvedFace): Bitmap? {
        val bitmap = cache[cacheKey(face)] ?: return null
        return if (bitmap.isRecycled) {
            cache.remove(cacheKey(face))
            null
        } else {
            bitmap
        }
    }

    /** Decodes the provider JPEG off the main thread; [onReady] runs on the main thread. */
    fun load(ctx: Context, face: ProviderClient.ResolvedFace, onReady: (Bitmap?) -> Unit) {
        peek(face)?.let {
            onReady(it)
            return
        }
        val key = cacheKey(face)
        executor.execute {
            val bitmap = try {
                ctx.contentResolver.openInputStream(face.contentUri())?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            } catch (t: Throwable) {
                HLog.w("face load failed ${face.fileKey}: ${t.javaClass.simpleName}: ${t.message}")
                null
            }
            if (bitmap != null) cache[key] = bitmap
            main.post { onReady(bitmap) }
        }
    }

    private val roundedCache = ConcurrentHashMap<Int, Bitmap>()

    /**
     * Transit detail page (layout honor_wallet_traffic_detail_pic_view) has NO view clipping,
     * so the 12dp card corner radius must be baked into the bitmap we hand it (re-transit §2).
     */
    fun needsBakedCorners(view: ImageView): Boolean {
        return try {
            val id = view.id
            if (id == 0) return false
            view.resources.getResourceEntryName(id).contains("iv_traffic_card_bg", ignoreCase = true)
        } catch (_: Throwable) {
            false
        }
    }

    /** Returns a 12dp-rounded copy of [bitmap] (cached per source bitmap identity). */
    fun roundedFor(view: ImageView, bitmap: Bitmap): Bitmap {
        if (bitmap.isRecycled) return bitmap
        if (!needsBakedCorners(view)) return bitmap
        val key = System.identityHashCode(bitmap)
        roundedCache[key]?.let { cached -> if (!cached.isRecycled) return cached }
        return try {
            val radius = 12f * view.resources.displayMetrics.density
            val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val rect = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
            val path = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }
            canvas.clipPath(path)
            canvas.drawBitmap(bitmap, 0f, 0f, paint)
            if (roundedCache.size > 8) roundedCache.clear()
            roundedCache[key] = out
            out
        } catch (_: Throwable) {
            bitmap
        }
    }
    /** Drops cached bitmaps (e.g. config version changed); safe to call from any thread. */
    fun invalidate() {
        cache.clear()
    }
}
