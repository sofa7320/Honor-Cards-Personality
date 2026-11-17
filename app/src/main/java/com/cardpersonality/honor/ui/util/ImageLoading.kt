package com.cardpersonality.honor.ui.util

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.cardpersonality.honor.data.StickerKind
import com.cardpersonality.honor.data.StickerSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Loads the source face image for the live preview (off the main thread). */
@Composable
fun rememberFaceImageBitmap(path: String?, maxDimension: Int = 2048): ImageBitmap? {
    // Round 26: include the file stamp so an overwritten import (same path) refreshes immediately.
    val stamp = com.cardpersonality.honor.FaceImageLoader.fileStamp(path)
    val state by produceState<ImageBitmap?>(initialValue = null, path, maxDimension, stamp) {
        value = if (path.isNullOrEmpty()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                FaceImageDecoder.decode(path, maxDimension)?.asImageBitmap()
            }
        }
    }
    return state
}

/** Loads all image-sticker bitmaps for a config (keyed by sticker id). */
@Composable
fun rememberStickerBitmaps(stickers: List<StickerSpec>): Map<String, ImageBitmap> {
    val paths = remember(stickers) {
        stickers.asSequence()
            .filter { it.kind == StickerKind.IMAGE && !it.imagePath.isNullOrEmpty() }
            .associate { it.id to it.imagePath!! }
    }
    val state by produceState<Map<String, ImageBitmap>>(initialValue = emptyMap(), paths) {
        if (paths.isEmpty()) {
            value = emptyMap()
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            val out = HashMap<String, ImageBitmap>(paths.size)
            for ((id, path) in paths) {
                FaceImageDecoder.decode(path, 1024)?.let { out[id] = it.asImageBitmap() }
            }
            out
        }
    }
    return state
}

/** Minimal decoder used by the UI; the renderer has its own cache for provider-side use. */
internal object FaceImageDecoder {
    fun decode(path: String, maxDimension: Int): Bitmap? {
        com.cardpersonality.honor.FaceImageLoader.load(path, maxDimension)?.let { return it }
        // Round 26: fallback decode when the cached/Lanczos path fails (large or unusual images),
        // so the preview never silently stays empty.
        return try {
            val opts = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            android.graphics.BitmapFactory.decodeFile(path, opts)
            var sample = 1
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                val longSide = maxOf(opts.outWidth, opts.outHeight)
                while (longSide / (sample * 2) >= maxDimension) sample *= 2
            }
            val decoded = android.graphics.BitmapFactory.decodeFile(
                path,
                android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
            android.util.Log.e(
                "CARD_PERSONALITY_PERF",
                "preview decode fallback path=$path max=$maxDimension ok=${decoded != null}",
            )
            decoded
        } catch (t: Throwable) {
            android.util.Log.e("CARD_PERSONALITY_PERF", "preview decode failed path=$path", t)
            null
        }
    }
}



