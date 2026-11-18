package com.cardpersonality.honor

import android.util.Log

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.util.LruCache
import com.cardpersonality.honor.data.CardFaceConfig
import com.cardpersonality.honor.data.StickerKind
import com.cardpersonality.honor.data.StickerSpec
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure `android.graphics` geometry + composition for the card face.
 *
 * The SAME geometry is used by:
 *  - [FaceRenderer] (final exported JPEG served to the wallet), and
 *  - the Compose preview (ui/components/CardFacePreview.kt) so preview == wallet result.
 *
 * Canvas is designed at 336x208 units (wallet aspect 1.6153846), exported at 6x = 2016x1248.
 * No rounded corners are baked in: the wallet clips the image via HwCardView (12dp).
 */
object FaceGeometry {
    const val DESIGN_WIDTH = 336f
    const val DESIGN_HEIGHT = 208f
    const val ASPECT = DESIGN_WIDTH / DESIGN_HEIGHT // 1.6153846

    /** Wallet package whose card face uses a different aspect/corner radius (欧加钱包). */
    const val OPPO_WALLET_PACKAGE = "com.finshell.wallet"

    /** Xiaomi smart card (小米智能卡): reference-proven 960:606 output (320:202 design units). */
    const val XIAOMI_WALLET_PACKAGE = "com.miui.tsmclient"

    /**
     * Per-wallet card-face geometry (design units + wallet-side corner radius).
     * Honor: 336:208 (corner 12dp, unchanged); OPPO/欧加: 328:202 (≈960:591, corner 14dp);
     * Xiaomi smart card: 320:202 (exactly 960:606 at 3x, the reference module's proven output size;
     * the reference bakes a 40px corner radius into the PNG — we keep the rectangular face and let
     * the wallet clip it, see work/re/xiaomi/XIAOMI-PORT.md "limitations").
     * The normalized 0..1 sticker coordinates are shared by all wallets, so configs stay compatible.
     */
    data class FaceSpec(
        val designWidth: Float,
        val designHeight: Float,
        val cornerRadiusDp: Float,
        val walletPackage: String?,
    ) {
        val aspect: Float get() = designWidth / designHeight
        val label: String get() = "${designWidth.toInt()} : ${designHeight.toInt()}"
    }

    val HONOR_SPEC = FaceSpec(DESIGN_WIDTH, DESIGN_HEIGHT, 12f, null)
    val OPPO_SPEC = FaceSpec(328f, 202f, 14f, OPPO_WALLET_PACKAGE)
    val XIAOMI_SPEC = FaceSpec(320f, 202f, 13f, XIAOMI_WALLET_PACKAGE)

    fun specFor(walletPackage: String?): FaceSpec = when (walletPackage) {
        OPPO_WALLET_PACKAGE -> OPPO_SPEC
        XIAOMI_WALLET_PACKAGE -> XIAOMI_SPEC
        else -> HONOR_SPEC
    }

    const val MIN_SCALE = 0.4f
    const val MAX_SCALE = 4f

    /** Default text size in design units when a sticker has sizeDp == 20. */
    const val BASE_TEXT_SIZE = 20f

    /** Default image sticker width as a fraction of the card width. */
    const val BASE_STICKER_WIDTH = 0.4f

    const val MAX_STICKER_ROTATION = 270f

    /** Placement of the source image inside the card canvas, in canvas pixels. */
    data class Placement(
        val scale: Float,
        val destWidth: Float,
        val destHeight: Float,
        val dx: Float,
        val dy: Float,
    )

    fun clampScale(scale: Float): Float = scale.coerceIn(MIN_SCALE, MAX_SCALE)

    fun clampRotation(rotation: Float): Float =
        rotation.coerceIn(-MAX_STICKER_ROTATION, MAX_STICKER_ROTATION)

    /** Cover-fit placement with user zoom/pan applied and clamped so no background leaks. */
    fun placement(
        imageWidth: Float,
        imageHeight: Float,
        canvasWidth: Float,
        canvasHeight: Float,
        cfg: CardFaceConfig,
    ): Placement {
        if (imageWidth <= 0f || imageHeight <= 0f) {
            return Placement(1f, 0f, 0f, 0f, 0f)
        }
        val cover = max(canvasWidth / imageWidth, canvasHeight / imageHeight)
        val scale = cover * clampScale(cfg.scale)
        val dw = imageWidth * scale
        val dh = imageHeight * scale
        val maxShiftX = max(0f, (dw - canvasWidth) / 2f)
        val maxShiftY = max(0f, (dh - canvasHeight) / 2f)
        val shiftX = (cfg.offsetX * canvasWidth).coerceIn(-maxShiftX, maxShiftX)
        val shiftY = (cfg.offsetY * canvasHeight).coerceIn(-maxShiftY, maxShiftY)
        return Placement(
            scale = scale,
            destWidth = dw,
            destHeight = dh,
            dx = (canvasWidth - dw) / 2f + shiftX,
            dy = (canvasHeight - dh) / 2f + shiftY,
        )
    }

    /**
     * Applies one pan/zoom gesture step to the source image transform.
     * Zoom keeps the [anchorX]/[anchorY] (canvas pixels) point stable; pan is in canvas pixels.
     */
    fun gestureTransform(
        cfg: CardFaceConfig,
        imageWidth: Float,
        imageHeight: Float,
        canvasWidth: Float,
        canvasHeight: Float,
        panX: Float,
        panY: Float,
        zoom: Float,
        anchorX: Float,
        anchorY: Float,
    ): CardFaceConfig {
        if (imageWidth <= 0f || imageHeight <= 0f || canvasWidth <= 0f || canvasHeight <= 0f) {
            return cfg
        }
        val before = placement(imageWidth, imageHeight, canvasWidth, canvasHeight, cfg)
        val newScale = clampScale(cfg.scale * zoom)
        val cover = max(canvasWidth / imageWidth, canvasHeight / imageHeight)
        val newPlacementScale = cover * newScale
        // image-space point under the gesture anchor (relative to placement origin)
        val imgX = (anchorX - before.dx) / before.scale
        val imgY = (anchorY - before.dy) / before.scale
        val newDw = imageWidth * newPlacementScale
        val newDh = imageHeight * newPlacementScale
        var newDx = anchorX - imgX * newPlacementScale
        var newDy = anchorY - imgY * newPlacementScale
        // apply the pan in canvas pixels
        newDx += panX
        newDy += panY
        val maxShiftX = max(0f, (newDw - canvasWidth) / 2f)
        val maxShiftY = max(0f, (newDh - canvasHeight) / 2f)
        val baseX = (canvasWidth - newDw) / 2f
        val baseY = (canvasHeight - newDh) / 2f
        val shiftX = (newDx - baseX).coerceIn(-maxShiftX, maxShiftX)
        val shiftY = (newDy - baseY).coerceIn(-maxShiftY, maxShiftY)
        return cfg.copy(
            scale = newScale,
            offsetX = if (canvasWidth > 0f) shiftX / canvasWidth else 0f,
            offsetY = if (canvasHeight > 0f) shiftY / canvasHeight else 0f,
        )
    }
}

/**
 * Typeface resolution shared by the exporter and the Compose preview so text styling is identical.
 *
 *  - Latin families: "sans-serif", "sans-serif-medium", "sans-serif-condensed", "sans-serif-black",
 *    "serif", "monospace", "cursive", "casual"
 *  - CJK families (device system fonts): "cjk-sans"(荣耀黑体 / 思源黑体 / Droid 中文),
 *    "cjk-noto"(思源黑体), "cjk-serif"(思源宋体), "cjk-droid"(安卓中文)
 *  - custom fonts: imported .ttf/.otf file path
 *
 * Latin/custom typefaces get a CJK fallback via [Typeface.CustomFallbackBuilder] (API 29+) so that
 * Chinese text also honours the selected family; on API 28 the primary typeface is used as-is.
 * Weight/italic are baked in via [Typeface.create] (API 28+), so preview and export are identical.
 */
object FaceTypefaces {
    const val DEFAULT_FAMILY = "sans-serif"

    private data class CjkFamily(val id: String, val label: String, val path: String?, val ttc: Boolean)

    private val CJK_CANDIDATES = listOf(
        CjkFamily("cjk-sans", "Honor Sans CN（中文）", "/system/fonts/HONORSansVFCN.ttf", ttc = false),
        CjkFamily("cjk-noto", "Noto Sans CJK（中文）", "/system/fonts/NotoSansCJK-Regular.ttc", ttc = true),
        CjkFamily("cjk-droid", "Droid Sans Chinese（中文）", "/system/fonts/DroidSansChinese.ttf", ttc = false),
        CjkFamily("cjk-serif", "Noto Serif CJK（中文）", "/system/fonts/NotoSerifCJK-Regular.ttc", ttc = true),
    )

    private val LATIN_FAMILIES: List<Pair<String, String>> = listOf(
        "sans-serif" to "Sans Serif（西文）",
        "sans-serif-medium" to "Sans Serif Medium（西文）",
        "sans-serif-condensed" to "Sans Serif Condensed（西文）",
        "sans-serif-black" to "Sans Serif Black（西文）",
        "serif" to "Serif（西文）",
        "monospace" to "Monospace（西文）",
        "cursive" to "Cursive（西文）",
        "casual" to "Casual（西文）",
    )

    /** Families offered by the font picker: Latin first, then the available Chinese families. */
    val BUILT_IN_FAMILIES: List<Pair<String, String>> by lazy {
        LATIN_FAMILIES + CJK_CANDIDATES
            .filter { it.path != null && File(it.path).canRead() }
            .map { it.id to it.label }
    }

    private val cache = HashMap<String, Typeface>()
    private var cjkFaceFile: Pair<String, Int>? = null
    private var cjkFaceResolved = false
    private var cjkFontFamily: android.graphics.fonts.FontFamily? = null

    /** First readable CJK system font face that actually contains 中 (file + ttc index). */
    @Synchronized
    private fun findCjkFace(): Pair<String, Int>? {
        if (cjkFaceResolved) return cjkFaceFile
        cjkFaceResolved = true
        val ordered = listOf("cjk-sans", "cjk-noto", "cjk-droid", "cjk-serif")
            .mapNotNull { id -> CJK_CANDIDATES.firstOrNull { it.id == id } }
        for (family in ordered) {
            val path = family.path ?: continue
            if (!File(path).canRead()) continue
            if (family.ttc) {
                for (index in 0..9) {
                    val tf = runCatching { Typeface.Builder(path).setTtcIndex(index).build() }.getOrNull()
                    if (tf != null && hasChineseGlyph(tf)) {
                        cjkFaceFile = path to index
                        return cjkFaceFile
                    }
                }
            } else {
                val tf = runCatching { Typeface.Builder(path).build() }.getOrNull()
                if (tf != null && hasChineseGlyph(tf)) {
                    cjkFaceFile = path to 0
                    return cjkFaceFile
                }
            }
        }
        return null
    }

    private fun fontFamilyOf(path: String, ttcIndex: Int): android.graphics.fonts.FontFamily? =
        runCatching {
            val font = android.graphics.fonts.Font.Builder(File(path))
                .setTtcIndex(ttcIndex)
                .build()
            android.graphics.fonts.FontFamily.Builder(font).build()
        }.getOrNull()

    @Synchronized
    private fun cjkFallbackFamily(): android.graphics.fonts.FontFamily? {
        if (cjkFontFamily != null) return cjkFontFamily
        val face = findCjkFace() ?: return null
        cjkFontFamily = fontFamilyOf(face.first, face.second)
        return cjkFontFamily
    }

    private fun hasChineseGlyph(tf: Typeface): Boolean = try {
        val paint = Paint()
        paint.typeface = tf
        paint.hasGlyph("中")
    } catch (_: Throwable) {
        false
    }

    private fun candidateFilesForFamily(familyName: String): List<String> = when (familyName) {
        "sans-serif", "sans-serif-medium", "sans-serif-condensed", "sans-serif-black" -> listOf(
            "/system/fonts/Roboto-Regular.ttf",
            "/system/fonts/RobotoFlex-Regular.ttf",
            "/system/fonts/HONORSansVFCN.ttf",
        )
        "serif" -> listOf(
            "/system/fonts/NotoSerif-Regular.ttf",
            "/system/fonts/NotoSerifCJK-Regular.ttc",
        )
        "monospace" -> listOf(
            "/system/fonts/RobotoMono-Regular.ttf",
            "/system/fonts/DroidSansMono.ttf",
        )
        else -> emptyList()
    }

    private fun latinBase(name: String): Typeface =
        cache.getOrPut("family:$name") {
            runCatching { Typeface.create(name, Typeface.NORMAL) }
                .getOrDefault(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL))
        }

    private fun customBase(path: String): Typeface =
        cache.getOrPut("file:$path") {
            runCatching { Typeface.createFromFile(path) }.getOrDefault(Typeface.DEFAULT)
        }

    /**
     * Builds a typeface with the system CJK family attached as fallback so Chinese glyphs use the
     * selected Latin family's fallback instead of the system default. API 29+ only (falls back to
     * the primary typeface on API 28).
     */
    private fun withCjkFallback(base: Typeface, primaryFile: String?, baseKey: String): Typeface {
        if (android.os.Build.VERSION.SDK_INT < 29) return base
        val family = primaryFile?.let { fontFamilyOf(it, 0) } ?: return base
        val cjk = cjkFallbackFamily() ?: return base
        return cache.getOrPut("fallback:$baseKey") {
            runCatching {
                Typeface.CustomFallbackBuilder(family)
                    .addCustomFallback(cjk)
                    .build()
            }.getOrDefault(base)
        }
    }

    private fun cjkTypeface(family: CjkFamily): Typeface? {
        val path = family.path ?: return null
        if (!File(path).canRead()) return null
        return cache.getOrPut("cjkbase:${family.id}") {
            val loaded: Typeface? = if (family.ttc) {
                (0 until 10).asSequence()
                    .mapNotNull { index ->
                        runCatching { Typeface.Builder(path).setTtcIndex(index).build() }.getOrNull()
                    }
                    .firstOrNull { tf -> hasChineseGlyph(tf) }
            } else {
                runCatching { Typeface.Builder(path).build() }.getOrNull()
            }
            loaded ?: Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
    }

    /** Resolves the final typeface for a sticker (family + optional imported font + weight/italic). */
    fun resolve(
        customFontPath: String?,
        family: String?,
        weight: Int,
        italic: Boolean,
    ): Typeface {
        val familyId = family?.takeIf { it.isNotBlank() } ?: DEFAULT_FAMILY
        val base: Typeface
        val baseKey: String
        if (!customFontPath.isNullOrEmpty()) {
            base = withCjkFallback(customBase(customFontPath), customFontPath, "custom:$customFontPath")
            baseKey = "custom:$customFontPath"
        } else {
            val cjk = CJK_CANDIDATES.firstOrNull {
                it.id == familyId && it.path != null && File(it.path).canRead()
            }
            if (cjk != null) {
                base = cjkTypeface(cjk) ?: latinBase(DEFAULT_FAMILY)
                baseKey = "cjk:${cjk.id}"
            } else {
                val primaryFile = candidateFilesForFamily(familyId)
                    .firstOrNull { File(it).canRead() }
                base = withCjkFallback(latinBase(familyId), primaryFile, "latin:$familyId")
                baseKey = "latin:$familyId"
            }
        }
        val w = weight.coerceIn(100, 900)
        return cache.getOrPut("final:$baseKey:$w:$italic") {
            try {
                Typeface.create(base, w, italic) ?: base
            } catch (_: Throwable) {
                base
            }
        }
    }

    @Synchronized
    fun invalidate() {
        cache.clear()
        cjkFaceFile = null
        cjkFaceResolved = false
        cjkFontFamily = null
    }
}

/** Small decode cache so repeated provider/thumbnail renders don't re-decode the same file. */
object FaceImageLoader {
    /** Byte-budgeted decode cache (round 26: prevents huge candidate bitmaps piling up). */
    private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Resolves a possibly-relative stored path against `filesDir`. */
    fun resolvePath(ctx: Context?, path: String?): String? {
        if (path.isNullOrEmpty()) return null
        if (path.startsWith("/")) return path
        val base = ctx?.filesDir ?: return path
        return File(base, path).absolutePath
    }

    /** Cheap change token for a source file (mtime + size); used as a preview state key. */
    fun fileStamp(path: String?): Long {
        if (path.isNullOrEmpty()) return 0L
        return try {
            val f = File(path)
            if (!f.isFile) 0L else f.lastModified() * 31L + f.length()
        } catch (_: Throwable) {
            0L
        }
    }

    /** Drops cached decodes of [path] (any maxDimension) so an updated file is re-read. */
    fun evict(path: String?) {
        if (path.isNullOrEmpty()) return
        val prefix = path + "@"
        val keys = runCatching { cache.snapshot().keys.toList() }.getOrDefault(emptyList())
        keys.filter { it.startsWith(prefix) }.forEach { cache.remove(it) }
        // Round 25: drop compressed thumbnail sidecars as well so an updated export is re-read.
        runCatching {
            File(path).parentFile?.listFiles()?.forEach { f ->
                if (f.name.startsWith(File(path).name + ".thumb")) f.delete()
            }
        }
    }

    fun load(path: String?, maxDimension: Int = 2560): Bitmap? {
        if (path.isNullOrEmpty()) return null
        val file = File(path)
        if (!file.isFile) return null
        val cacheKey = path + "@" + maxDimension
        cache.get(cacheKey)?.let { return it }
        // Round 25: list thumbnails keep a compressed WebP (q80) cache next to the source so
        // repeated Home refreshes do not re-run Lanczos on the full-resolution export.
        val isThumb = maxDimension in 1..640
        val thumbFile = if (isThumb) File(file.parentFile, file.name + ".thumb$maxDimension.webp") else null
        val bitmap = try {
            if (thumbFile != null && thumbFile.isFile &&
                thumbFile.lastModified() >= file.lastModified() &&
                thumbFile.length() > 0L
            ) {
                BitmapFactory.decodeFile(thumbFile.absolutePath)
            } else {
                decodeSampled(file, maxDimension)
            } ?: return null
        } catch (_: Throwable) {
            return null
        }
        if (isThumb && thumbFile != null && !thumbFile.isFile) {
            runCatching {
                FileOutputStream(thumbFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.WEBP, 80, out)
                    out.flush()
                }
            }
        }
        cache.put(cacheKey, bitmap)
        return bitmap
    }

    private fun decodeSampled(file: File, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        var longSide = max(bounds.outWidth, bounds.outHeight)
        while (longSide / (sample * 2) >= maxDimension) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        if (maxDimension > 0) {
            val longSide = max(decoded.width, decoded.height)
            if (longSide > maxDimension) {
                // Round 26 perf: previews/thumbnails use cheap bilinear here; Lanczos3 is reserved
                // for the final export path (renderer) where quality actually matters.
                val ratio = maxDimension.toFloat() / longSide
                val dw = (decoded.width * ratio).roundToInt().coerceAtLeast(1)
                val dh = (decoded.height * ratio).roundToInt().coerceAtLeast(1)
                return Bitmap.createScaledBitmap(decoded, dw, dh, true)
            }
        }
        return decoded
    }
}

/**
 * Composes the final card-face bitmap. Deterministic: identical inputs produce identical output.
 * Output is rectangular (the wallet applies its own 12dp rounded clip).
 */
object FaceRenderer {
    const val EXPORT_WIDTH = 2016
    const val EXPORT_HEIGHT = 1248
    const val JPEG_QUALITY = 92

    /** Round 25: user-selected export multiplier (1x..10x); null = adaptive (2x..6x). */
    @Volatile
    private var exportScaleOverride: Int? = null

    /** Sets the persistent export quality override (1..10) for subsequent renders/exports. */
    fun setExportScale(scale: Int?) {
        exportScaleOverride = scale?.coerceIn(1, 10)
    }

    fun currentExportScale(): Int = exportScaleOverride ?: 0

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    /**
     * Adaptive export size: enough resolution to keep the base image at (at least) its native
     * sharpness without an extra upscale/downscale cycle. The wallet renders its face from a
     * 672×416-ish CDN image, so a 672×416 capture is exported at 672×416 (2x design) instead of
     * being upscaled to 2016×1248 and downscaled by the wallet (two resamples → soft). Phone photos
     * (≥2016px wide) still export at the full 6x = 2016×1248.
     */
    fun adaptiveExportSize(
        ctx: Context?,
        cfg: CardFaceConfig,
        walletPackage: String? = null,
    ): android.util.Size? {
        val spec = FaceGeometry.specFor(walletPackage)
        exportScaleOverride?.let { scale ->
            return android.util.Size(
                spec.designWidth.toInt() * scale,
                spec.designHeight.toInt() * scale,
            )
        }
        val image = FaceImageLoader.load(FaceImageLoader.resolvePath(ctx, cfg.imagePath))
        val baseWidth = image?.width ?: 0
        val scale = if (baseWidth <= 0) {
            6
        } else {
            (baseWidth.toFloat() / spec.designWidth).roundToInt().coerceIn(2, 6)
        }
        return android.util.Size(
            spec.designWidth.toInt() * scale,
            spec.designHeight.toInt() * scale,
        )
    }

    fun render(
        ctx: Context?,
        cfg: CardFaceConfig,
        width: Int = 0,
        height: Int = 0,
        walletPackage: String? = null,
    ): Bitmap? {
        val spec = FaceGeometry.specFor(walletPackage)
        val (w, h) = if (width > 0 && height > 0) {
            width to height
        } else {
            val size = adaptiveExportSize(ctx, cfg, walletPackage) ?: return null
            size.width to size.height
        }
        if (w <= 0 || h <= 0) return null
        val started = System.nanoTime()
        return try {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val drewBackground = draw(
                ctx, cfg, canvas, w.toFloat(), h.toFloat(), spec.designWidth, spec.designHeight,
            )
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            Log.i(
                "CARD_PERSONALITY_PERF",
                "render|timing size=${w}x$h bg=${cfg.imagePath != null} drewBg=$drewBackground ms=$elapsedMs stickers=${cfg.stickers.size}",
            )
            if (cfg.imagePath != null && !drewBackground) {
                // Round 26: never export a background-less card (elements-only artifact).
                Log.e("CARD_PERSONALITY_PERF", "render aborted: background decode failed path=${cfg.imagePath}")
                bitmap.recycle()
                null
            } else {
                bitmap
            }
        } catch (t: Throwable) {
            Log.e("CARD_PERSONALITY_PERF", "render failed", t)
            null
        }
    }

    /** Renders and writes a JPEG atomically (tmp file + rename). Returns true on success. */
    fun renderToFile(
        ctx: Context?,
        cfg: CardFaceConfig,
        outFile: File,
        width: Int = 0,
        height: Int = 0,
        walletPackage: String? = null,
    ): Boolean {
        val bitmap = render(ctx, cfg, width, height, walletPackage) ?: return false
        // Round 26: reject a blank output (e.g. background silently missing) instead of writing a
        // corrupted card face.
        if (cfg.imagePath != null && !hasVisualVariation(bitmap)) {
            Log.e("CARD_PERSONALITY_PERF", "render aborted: output looks blank path=${cfg.imagePath}")
            bitmap.recycle()
            return false
        }
        return try {
            val started = System.nanoTime()
            outFile.parentFile?.mkdirs()
            val tmp = File(outFile.absolutePath + ".tmp")
            FileOutputStream(tmp).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.flush()
            }
            val renamed = tmp.renameTo(outFile) || run {
                outFile.delete()
                tmp.renameTo(outFile)
            }
            Log.i(
                "CARD_PERSONALITY_PERF",
                "render|timing jpeg bytes=${outFile.length()} ms=${(System.nanoTime() - started) / 1_000_000}",
            )
            renamed
        } catch (_: Throwable) {
            false
        } finally {
            bitmap.recycle()
            // Round 29: release the scaled background cache after a save so a 10x export does not
            // keep three full-size bitmaps (decoded + scaled + output) resident.
            ScaledFaceCache.clear()
        }
    }

    /** Core composition; shared by [render] (and the preview replicates its math). */
    fun draw(
        ctx: Context?,
        cfg: CardFaceConfig,
        canvas: Canvas,
        width: Float,
        height: Float,
        designWidth: Float = FaceGeometry.DESIGN_WIDTH,
        designHeight: Float = FaceGeometry.DESIGN_HEIGHT,
    ): Boolean {
        var drewBackground = false
        // 1. background
        bgPaint.shader = LinearGradient(
            0f, 0f, width, height,
            intArrayOf(0xFF2B2F3A.toInt(), 0xFF16181D.toInt(), 0xFF0E0F12.toInt()),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width, height, bgPaint)
        bgPaint.shader = null

        // 2. source image (cover fit + user zoom/pan)
        val imagePath = FaceImageLoader.resolvePath(ctx, cfg.imagePath)
        // Round 26: cap the decoded size near the output size (avoid OOM on 8K photos), and retry
        // with a fresh full decode when the cached/sampled decode fails (background loss bug).
        // Round 28: cap the decoded background at the output long side (3072 max) to keep the 10x
        // save PSS down; any remaining upscale is done by the canvas.
        val decodeCap = max(width.toInt(), height.toInt()).coerceIn(1024, 3072)
        var image = FaceImageLoader.load(imagePath, decodeCap)
        if (image == null && !imagePath.isNullOrEmpty()) {
            FaceImageLoader.evict(imagePath)
            image = FaceImageLoader.load(imagePath, 4096)
            Log.e("CARD_PERSONALITY_PERF", "render|background decode retry path=$imagePath cap=$decodeCap -> ${image != null}")
        }
        if (image != null && !image.isRecycled && image.width > 0 && image.height > 0) {
            drewBackground = true
            val p = FaceGeometry.placement(
                image.width.toFloat(), image.height.toFloat(), width, height, cfg,
            )
            val dst = Rect(
                p.dx.toInt(),
                p.dy.toInt(),
                (p.dx + p.destWidth).toInt(),
                (p.dy + p.destHeight).toInt(),
            )
            if (dst.width() > 0 && dst.height() > 0) {
                // Round 25: high-quality downscale with Lanczos3 (linear light) instead of the
                // canvas' bilinear filtering; draw 1:1 afterwards.
                val base = if (image.width > dst.width() + 1 || image.height > dst.height() + 1) {
                    ScaledFaceCache.get(imagePath, image, dst.width(), dst.height())
                } else {
                    image
                }
                val src = Rect(0, 0, base.width, base.height)
                if (cfg.flipX || cfg.flipY) {
                    val centerX = p.dx + p.destWidth / 2f
                    val centerY = p.dy + p.destHeight / 2f
                    canvas.save()
                    canvas.scale(
                        if (cfg.flipX) -1f else 1f,
                        if (cfg.flipY) -1f else 1f,
                        centerX,
                        centerY,
                    )
                    canvas.drawBitmap(base, src, dst, bitmapPaint)
                    canvas.restore()
                } else {
                    canvas.drawBitmap(base, src, dst, bitmapPaint)
                }
            }
        }

        // 3. user stickers: list index 0 is the TOP-most layer, so draw in reverse order.
        val designScale = width / designWidth
        for (spec in cfg.stickers.asReversed()) {
            if (!spec.visible) continue
            when (spec.kind) {
                StickerKind.IMAGE -> drawImageSticker(ctx, canvas, spec, width, height)
                else -> drawTextSticker(canvas, spec, width, height, designScale)
            }
        }
        return drewBackground
    }

    private fun drawTextSticker(
        canvas: Canvas,
        spec: StickerSpec,
        width: Float,
        height: Float,
        designScale: Float,
    ) {
        val text = spec.text.ifBlank { return }
        val typeface = FaceTypefaces.resolve(
            customFontPath = spec.customFontPath,
            family = spec.fontFamily,
            weight = spec.fontWeight,
            italic = spec.italic,
        )
        val fontSize = spec.sizeDp.coerceIn(4f, 400f) * designScale
        val stickerAlpha = spec.alpha.coerceIn(0f, 1f)
        val textColor = withAlpha(if (spec.color == -1) Color.WHITE else spec.color, stickerAlpha)
        val fillPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            color = textColor
            textSize = fontSize
            this.typeface = typeface
            isUnderlineText = spec.underline
            // Round-34 fix: glow is drawn by the explicit blurred fill pass below.
        }
        // Round 34: explicit blurred glow pass (same as the preview); reliable for large radii where a
    // shadow layer can disappear, and keeps 3x/10x exports consistent with the preview.
    // Round-34 fix: blur the glyph MASK itself (fill style + BlurMaskFilter) so the result is a
    // soft halo around the letters. A blurred STROKE with width 2r filled a solid band that looked
    // like a red rectangle for large radii (and the pass was previously never drawn).
    // Round 36: multi-layer glow. A single fixed-alpha blur loses energy with radius (r48 weaker
    // than r24) and r1 is invisible; an inner sharp core + larger outer halo + source-over repeats
    // keep all radii visible and roughly monotonic. Same rule as the preview.
    val glowLayers: List<Pair<Paint, Int>> = if (spec.glow && spec.glowRadius > 0f) {
        val rDp = spec.glowRadius
        val outerRadius = (rDp * designScale).coerceAtLeast(2f)
        val outerAlpha = (0.35f + 0.55f * (rDp / 48f)).coerceIn(0.35f, 0.9f)
        val innerRadius = (outerRadius * 0.4f).coerceAtLeast(2f)
        fun layer(radius: Float, alpha: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            color = withAlpha(spec.glowColor, stickerAlpha * alpha)
            textSize = fontSize
            this.typeface = typeface
            style = Paint.Style.FILL
            maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
        }
        listOf(layer(outerRadius, outerAlpha) to 2, layer(innerRadius, 0.85f) to 2)
    } else {
        emptyList()
    }
    val strokePaint = if (spec.stroke && spec.strokeWidth > 0f) {
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = Paint.Align.CENTER
                color = withAlpha(spec.strokeColor, stickerAlpha)
                textSize = fontSize
                this.typeface = typeface
                style = Paint.Style.STROKE
                strokeWidth = spec.strokeWidth * designScale
                strokeJoin = Paint.Join.ROUND
                isUnderlineText = spec.underline
            }
        } else {
            null
        }
        val cx = spec.cx * width
        val cy = spec.cy * height
        val lines = text.split('\n')
        val fm = fillPaint.fontMetrics
        val lineHeight = (fm.descent - fm.ascent) * 1.05f
        val totalHeight = lineHeight * lines.size
        val baselines = FloatArray(lines.size)
        var baseline = cy - totalHeight / 2f - fm.ascent
        for (i in lines.indices) {
            baselines[i] = baseline
            baseline += lineHeight
        }
        canvas.save()
        canvas.rotate(FaceGeometry.clampRotation(spec.rot), cx, cy)
        // Round 35: three passes. All glow lines first (later rows must not cover earlier glow),
        // then the outline, then the fill on top.
        // Glow layers from largest to smallest, repeated source-over to build visible alpha.
        for ((paint, repeats) in glowLayers) {
            repeat(repeats) {
                for (i in lines.indices) canvas.drawText(lines[i], cx, baselines[i], paint)
            }
        }
        if (strokePaint != null) {
            for (i in lines.indices) canvas.drawText(lines[i], cx, baselines[i], strokePaint)
        }
        for (i in lines.indices) canvas.drawText(lines[i], cx, baselines[i], fillPaint)
        canvas.restore()
    }

    private fun drawImageSticker(
        ctx: Context?,
        canvas: Canvas,
        spec: StickerSpec,
        width: Float,
        height: Float,
    ) {
        val path = FaceImageLoader.resolvePath(ctx, spec.imagePath) ?: return
        val dstW = (spec.w.coerceIn(0.05f, 2f) * width).coerceAtLeast(8f)
        // Round 26: decode stickers at the resolution the final output actually needs (was a fixed
        // 1536 px -> blurry when a sticker covers a large part of a high-res export).
        val stickerCap = (dstW * 1.25f).toInt().coerceIn(1024, 3072)
        val image = FaceImageLoader.load(path, stickerCap) ?: return
        if (image.isRecycled || image.width <= 0) return
        val dstH = dstW * image.height / image.width
        val cx = spec.cx * width
        val cy = spec.cy * height
        canvas.save()
        canvas.rotate(FaceGeometry.clampRotation(spec.rot), cx, cy)
        if (spec.flipX || spec.flipY) {
            canvas.scale(
                if (spec.flipX) -1f else 1f,
                if (spec.flipY) -1f else 1f,
                cx,
                cy,
            )
        }
        val dst = Rect(
            (cx - dstW / 2f).toInt(),
            (cy - dstH / 2f).toInt(),
            (cx + dstW / 2f).toInt(),
            (cy + dstH / 2f).toInt(),
        )
        if (dst.width() > 0 && dst.height() > 0) {
            val base = if (image.width > dst.width() + 1 || image.height > dst.height() + 1) {
                runCatching {
                    Bitmap.createScaledBitmap(image, dst.width(), dst.height(), true)
                }.getOrDefault(image)
            } else {
                image
            }
            val alpha = (spec.alpha.coerceIn(0f, 1f) * 255f).toInt()
            val src = Rect(0, 0, base.width, base.height)
            if (alpha >= 255) {
                canvas.drawBitmap(base, src, dst, bitmapPaint)
            } else {
                val paint = Paint(bitmapPaint).apply { this.alpha = alpha }
                canvas.drawBitmap(base, src, dst, paint)
            }
        }
        canvas.restore()
    }

    /** Cheap blank-image check: samples a grid and requires some colour variance. */
    private fun hasVisualVariation(bitmap: Bitmap): Boolean {
        if (bitmap.width <= 0 || bitmap.height <= 0) return false
        var minR = 255
        var maxR = 0
        var minG = 255
        var maxG = 0
        var minB = 255
        var maxB = 0
        val stepX = (bitmap.width / 16).coerceAtLeast(1)
        val stepY = (bitmap.height / 16).coerceAtLeast(1)
        var y = stepY / 2
        while (y < bitmap.height) {
            var x = stepX / 2
            while (x < bitmap.width) {
                val color = bitmap.getPixel(x, y)
                val r = (color ushr 16) and 0xFF
                val g = (color ushr 8) and 0xFF
                val b = color and 0xFF
                if (r < minR) minR = r
                if (r > maxR) maxR = r
                if (g < minG) minG = g
                if (g > maxG) maxG = g
                if (b < minB) minB = b
                if (b > maxB) maxB = b
                x += stepX
            }
            y += stepY
        }
        return (maxR - minR) + (maxG - minG) + (maxB - minB) > 24
    }

    /** Multiplies an ARGB colour's alpha by [factor] (0..1). */
    /** Glow colour with an alpha floor so radius=1 stays visible after blur. */
    internal fun glowColorWithFloor(color: Int, stickerAlpha: Float): Int {
        val base = withAlpha(color, stickerAlpha)
        val floorAlpha = (0.35f * 255f).toInt()
        val a = maxOf(Color.alpha(base), floorAlpha)
        return (base and 0x00FFFFFF) or (a shl 24)
    }

    internal fun withAlpha(color: Int, factor: Float): Int {
        val a = (Color.alpha(color) * factor.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    /** Size of a sticker in canvas pixels (used by preview hit-testing/selection chrome). */
    fun stickerSizePx(
        spec: StickerSpec,
        canvasWidth: Float,
        canvasHeight: Float,
        textMeasurer: ((StickerSpec) -> Pair<Float, Float>)? = null,
    ): Pair<Float, Float> {
        return when (spec.kind) {
            StickerKind.IMAGE -> {
                val aspect = spec.h.let { if (it > 0f) it / spec.w.coerceAtLeast(0.02f) else 0.6f }
                val w = spec.w * canvasWidth
                w to (w * aspect.coerceIn(0.1f, 3f))
            }
            else -> textMeasurer?.invoke(spec) ?: (spec.sizeDp * 4f to spec.sizeDp * 1.4f)
        }
    }

    fun stickerCenterPx(spec: StickerSpec, canvasWidth: Float, canvasHeight: Float): Pair<Float, Float> =
        spec.cx * canvasWidth to spec.cy * canvasHeight
}

/** Utility: keeps a positive, finite float. */
internal fun Float.safe(): Float = if (isNaN() || isInfinite()) 0f else this

internal fun Int.clamp255(): Int = min(max(this, 0), 255)

/**
 * Round 26 perf rewrite: streaming Lanczos3 (a=3) with sRGB<->linear lookup tables.
 *
 * - No full-image FloatArray: one source row is read with Bitmap.getPixels, horizontally
 *   filtered into a small row buffer, then vertically accumulated into one output row
 *   (only the rows inside the current vertical support are cached).
 * - sRGB<->linear uses 256-entry / 4096-entry LUTs instead of per-pixel pow().
 * - When the source is more than 2x the target, an integer box pre-shrink runs first
 *   ("two-level downscale"), so the expensive convolution stays near the target size.
 * - Any failure/OOM falls back to Bitmap.createScaledBitmap (bilinear) instead of crashing.
 */
object Lanczos3 {

    private const val A = 3f
    private const val LUT_SIZE = 4096

    private val SRGB_TO_LINEAR = FloatArray(256) { i ->
        val c = i / 255f
        if (c <= 0.04045f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.2).toFloat()
    }
    private val LINEAR_TO_SRGB = IntArray(LUT_SIZE + 1) { i ->
        val c = i.toFloat() / LUT_SIZE
        val s = if (c <= 0.0031308f) {
            c * 12.92f
        } else {
            (1.055f * Math.pow(c.toDouble(), 1.0 / 2.2) - 0.055f).toFloat()
        }
        (s * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    fun scale(src: Bitmap, dstWidth: Int, dstHeight: Int, linearLight: Boolean = true): Bitmap {
        if (dstWidth <= 0 || dstHeight <= 0) return src
        if (src.width == dstWidth && src.height == dstHeight) return src
        if (src.width <= 0 || src.height <= 0) return src
        return try {
            // Two-level downscale: integer box pre-shrink when the source is far larger.
            val pre = boxPreShrink(src, dstWidth, dstHeight)
            val out = convolve(pre, dstWidth, dstHeight, linearLight)
            if (pre !== src && pre !== out) runCatching { pre.recycle() }
            out
        } catch (t: Throwable) {
            android.util.Log.w(
                "CARD_PERSONALITY_PERF",
                "lanczos fallback ${src.width}x${src.height}->${dstWidth}x$dstHeight ${t.javaClass.simpleName}",
            )
            try {
                Bitmap.createScaledBitmap(src, dstWidth, dstHeight, true)
            } catch (_: Throwable) {
                src
            }
        }
    }

    /** Integer box pre-shrink so convolution runs at <= ~2x target size. */
    private fun boxPreShrink(src: Bitmap, dstW: Int, dstH: Int): Bitmap {
        val factor = minOf(src.width / (dstW * 2), src.height / (dstH * 2))
        if (factor < 2) return src
        val outW = (src.width / factor).coerceAtLeast(1)
        val outH = (src.height / factor).coerceAtLeast(1)
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val row = IntArray(src.width)
        val sums = IntArray(outW * 4)
        val outRow = IntArray(outW)
        val divisor = factor * factor
        for (oy in 0 until outH) {
            java.util.Arrays.fill(sums, 0)
            for (ay in 0 until factor) {
                val sy = (oy * factor + ay).coerceAtMost(src.height - 1)
                src.getPixels(row, 0, src.width, 0, sy, src.width, 1)
                for (ox in 0 until outW) {
                    val base = ox * 4
                    for (ax in 0 until factor) {
                        val sx = (ox * factor + ax).coerceAtMost(src.width - 1)
                        val p = row[sx]
                        sums[base] += (p ushr 24) and 0xFF
                        sums[base + 1] += (p ushr 16) and 0xFF
                        sums[base + 2] += (p ushr 8) and 0xFF
                        sums[base + 3] += p and 0xFF
                    }
                }
            }
            for (ox in 0 until outW) {
                val base = ox * 4
                val a = sums[base] / divisor
                val r = sums[base + 1] / divisor
                val g = sums[base + 2] / divisor
                val b = sums[base + 3] / divisor
                outRow[ox] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            out.setPixels(outRow, 0, outW, 0, oy, outW, 1)
        }
        return out
    }
    private fun convolve(src: Bitmap, dstW: Int, dstH: Int, linearLight: Boolean): Bitmap {
        val sw = src.width
        val sh = src.height
        // Horizontal kernels (source pixels -> dst columns).
        val xScale = sw.toDouble() / dstW
        val xSupport = A * maxOf(1.0, xScale)
        val xStart = IntArray(dstW)
        val xWeights = arrayOfNulls<FloatArray>(dstW)
        for (dx in 0 until dstW) {
            val center = (dx + 0.5) * xScale - 0.5
            val start = kotlin.math.floor(center - xSupport).toInt()
            val end = kotlin.math.ceil(center + xSupport).toInt()
            val w = FloatArray(end - start + 1)
            var sum = 0f
            for (sx in start..end) {
                val value = kernel(((sx - center) / xSupport).toFloat())
                w[sx - start] = value
                sum += value
            }
            if (sum != 0f) for (i in w.indices) w[i] /= sum
            xStart[dx] = start
            xWeights[dx] = w
        }
        // Vertical kernels per output row.
        val yScale = sh.toDouble() / dstH
        val ySupport = A * maxOf(1.0, yScale)
        val yStart = IntArray(dstH)
        val yWeights = arrayOfNulls<FloatArray>(dstH)
        for (dy in 0 until dstH) {
            val center = (dy + 0.5) * yScale - 0.5
            val start = kotlin.math.floor(center - ySupport).toInt()
            val end = kotlin.math.ceil(center + ySupport).toInt()
            val w = FloatArray(end - start + 1)
            var sum = 0f
            for (sy in start..end) {
                val value = kernel(((sy - center) / ySupport).toFloat())
                w[sy - start] = value
                sum += value
            }
            if (sum != 0f) for (i in w.indices) w[i] /= sum
            yStart[dy] = start
            yWeights[dy] = w
        }

        val out = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        val srcRow = IntArray(sw)
        val hCache = HashMap<Int, FloatArray>()
        val accum = FloatArray(dstW * 4)
        val outRow = IntArray(dstW)

        for (dy in 0 until dstH) {
            val start = yStart[dy]
            val weights = yWeights[dy]!!
            // Drop rows no longer needed by future output rows.
            if (dy > 0) {
                val minKeep = yStart[dy]
                hCache.keys.toList().forEach { if (it < minKeep) hCache.remove(it) }
            }
            java.util.Arrays.fill(accum, 0f)
            for (k in weights.indices) {
                val sy = (start + k).coerceIn(0, sh - 1)
                val w = weights[k]
                val h = hCache[sy] ?: horizontalRow(
                    src, srcRow, sw, dstW, xStart, xWeights, sy, linearLight,
                ).also { hCache[sy] = it }
                var i = 0
                while (i < accum.size) {
                    accum[i] += h[i] * w
                    i++
                }
            }
            // Convert one output row using the LUT.
            var x = 0
            while (x < dstW) {
                val b = x * 4
                val a = accum[b + 3].coerceIn(0f, 1f)
                val r = accum[b].coerceIn(0f, 1f)
                val g = accum[b + 1].coerceIn(0f, 1f)
                val bl = accum[b + 2].coerceIn(0f, 1f)
                val ri = if (linearLight) LINEAR_TO_SRGB[(r * LUT_SIZE).toInt().coerceIn(0, LUT_SIZE)] else (r * 255f).toInt()
                val gi = if (linearLight) LINEAR_TO_SRGB[(g * LUT_SIZE).toInt().coerceIn(0, LUT_SIZE)] else (g * 255f).toInt()
                val bi = if (linearLight) LINEAR_TO_SRGB[(bl * LUT_SIZE).toInt().coerceIn(0, LUT_SIZE)] else (bl * 255f).toInt()
                outRow[x] = ((a * 255f + 0.5f).toInt() shl 24) or (ri shl 16) or (gi shl 8) or bi
                x++
            }
            out.setPixels(outRow, 0, dstW, 0, dy, dstW, 1)
        }
        return out
    }

    private fun horizontalRow(
        src: Bitmap,
        srcRow: IntArray,
        sw: Int,
        dstW: Int,
        xStart: IntArray,
        xWeights: Array<FloatArray?>,
        sy: Int,
        linearLight: Boolean,
    ): FloatArray {
        src.getPixels(srcRow, 0, sw, 0, sy, sw, 1)
        val result = FloatArray(dstW * 4)
        for (dx in 0 until dstW) {
            val start = xStart[dx]
            val weights = xWeights[dx]!!
            var r = 0f
            var g = 0f
            var b = 0f
            var a = 0f
            for (k in weights.indices) {
                val sx = (start + k).coerceIn(0, sw - 1)
                val p = srcRow[sx]
                val w = weights[k]
                val pr = (p ushr 16) and 0xFF
                val pg = (p ushr 8) and 0xFF
                val pb = p and 0xFF
                val pa = (p ushr 24) and 0xFF
                if (linearLight) {
                    r += SRGB_TO_LINEAR[pr] * w
                    g += SRGB_TO_LINEAR[pg] * w
                    b += SRGB_TO_LINEAR[pb] * w
                } else {
                    r += pr * w
                    g += pg * w
                    b += pb * w
                }
                a += pa * w
            }
            val base = dx * 4
            result[base] = r
            result[base + 1] = g
            result[base + 2] = b
            result[base + 3] = a / 255f
            if (!linearLight) {
                result[base] /= 255f
                result[base + 1] /= 255f
                result[base + 2] /= 255f
            }
        }
        return result
    }

    private fun kernel(t: Float): Float {
        val x = kotlin.math.abs(t)
        if (x >= A) return 0f
        return if (x < 1e-6f) {
            1f
        } else {
            val pix = Math.PI.toFloat() * x
            (kotlin.math.sin(pix) / pix) * (kotlin.math.sin(pix / A) / (pix / A))
        }
    }
}

/** Small bitmap cache for the Lanczos-scaled export background (source stamp + target size key). */
object ScaledFaceCache {
    private val cache = object : android.util.LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun clear() {
        runCatching { cache.evictAll() }
    }

    fun get(path: String?, src: Bitmap, dstWidth: Int, dstHeight: Int): Bitmap {
        val key = (path ?: "") + "@" + FaceImageLoader.fileStamp(path) +
            "@" + dstWidth + "x" + dstHeight
        cache.get(key)?.let { if (!it.isRecycled) return it }
        val started = System.nanoTime()
        val scaled = runCatching { Lanczos3.scale(src, dstWidth, dstHeight) }.getOrDefault(src)
        android.util.Log.i(
            "CARD_PERSONALITY_PERF",
            "lanczos scale ${src.width}x${src.height}->${dstWidth}x$dstHeight " +
                "ms=${(System.nanoTime() - started) / 1_000_000}",
        )
        if (scaled !== src) runCatching { cache.put(key, scaled) }
        return scaled
    }
}










