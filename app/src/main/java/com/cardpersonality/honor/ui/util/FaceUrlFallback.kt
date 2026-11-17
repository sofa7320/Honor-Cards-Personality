package com.cardpersonality.honor.ui.util

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.cardpersonality.honor.data.CardKey
import com.cardpersonality.honor.data.FaceRepository
import com.cardpersonality.honor.data.Hashing
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Round 24: "native wallet face" fallback for the editor.
 *
 * Some cards (notably Honor access cards whose face was customized inside the wallet) are reported
 * with an original face URL (`CardInfo.faceUrl` / cardPicUrl) but the module has not captured a
 * local snapshot yet (the wallet had not rendered the card after a cold start). Instead of showing
 * "no image", the panel downloads the URL directly and caches it as the editor background/preview.
 *
 * Classic access cards (faceId < 1000 / no URL) never reach this path — the caller only passes a
 * non-empty URL for cards that actually reported a native custom face.
 */
object FaceUrlFallback {

    private const val TAG = "CardPersonalityFaceUrl"
    private const val DEBOUNCE_MS = 2_000L
    private const val MAX_DOWNLOAD_BYTES = 16 * 1024 * 1024L
    private const val MAX_DIMENSION = 2560

    private val lastAttempt = ConcurrentHashMap<String, Long>()

    /**
     * Returns a local image file for [faceUrl], downloading it into the originals cache when no
     * snapshot exists yet. The file is marked with a sidecar "faceUrl-fallback" source file.
     * Debounced per card (2 s) so repeated editor opens do not re-download.
     */
    fun ensureDownloaded(ctx: Context, type: String, id: String, faceUrl: String?): File? {
        if (faceUrl.isNullOrBlank() || type.isEmpty() || id.isEmpty() ||
            id == CardKey.TYPE_DEFAULT_ID
        ) {
            return null
        }
        // Any existing snapshot (hook-captured OR previously downloaded cache) wins.
        FaceRepository.originalSnapshotFile(ctx, type, id)?.let { return it }

        val key = "$type:$id"
        val now = System.currentTimeMillis()
        if (now - (lastAttempt[key] ?: 0L) < DEBOUNCE_MS) return null
        lastAttempt[key] = now

        val sha = Hashing.sha256Hex(CardKey(type, id).fileKey)
        val dir = FaceRepository.originalsDir(ctx).apply { mkdirs() }
        val target = File(dir, "$sha.jpg")
        return try {
            val ok = if (faceUrl.startsWith("file://") || faceUrl.startsWith("/")) {
                copyLocal(faceUrl.removePrefix("file://"), target)
            } else if (faceUrl.startsWith("http://") || faceUrl.startsWith("https://")) {
                download(faceUrl, target)
            } else {
                false
            }
            if (!ok || !target.isFile || target.length() == 0L) {
                target.delete()
                null
            } else {
                if (!isDecodable(target)) {
                    target.delete()
                    null
                } else {
                    runCatching {
                        File(dir, "$sha.source").writeText("faceUrl-fallback", Charsets.UTF_8)
                    }
                    Log.i(TAG, "faceUrl fallback cached type=$type id=${id.take(8)} bytes=${target.length()}")
                    target
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "faceUrl fallback failed type=$type id=${id.take(8)}", t)
            runCatching { target.delete() }
            null
        }
    }

    private fun download(url: String, target: File): Boolean {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "CardPersonality/1.0")
        }
        try {
            if (connection.responseCode !in 200..299) return false
            if (connection.contentLengthLong > MAX_DOWNLOAD_BYTES) return false
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > MAX_DOWNLOAD_BYTES) return false
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
            return true
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun copyLocal(path: String, target: File): Boolean {
        val source = File(path)
        if (!source.isFile || source.length() <= 0L || source.length() > MAX_DOWNLOAD_BYTES) return false
        source.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        }
        return true
    }

    private fun isDecodable(file: File): Boolean {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            w > 0 && h > 0 && w <= MAX_DIMENSION && h <= MAX_DIMENSION
        } catch (_: Throwable) {
            false
        }
    }
}
