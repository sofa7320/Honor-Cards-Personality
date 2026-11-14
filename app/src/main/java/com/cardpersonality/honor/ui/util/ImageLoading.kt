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
    val state by produceState<ImageBitmap?>(initialValue = null, path, maxDimension) {
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
        return com.cardpersonality.honor.FaceImageLoader.load(path, maxDimension)
    }
}
