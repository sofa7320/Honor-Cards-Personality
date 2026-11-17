package com.cardpersonality.honor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
    private val cache = object : LruCache<String, Bitmap>(12) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    /** Resolves a possibly-relative stored path against `filesDir`. */
    fun resolvePath(ctx: Context?, path: String?): String? {
        if (path.isNullOrEmpty()) return null
        if (path.startsWith("/")) return path
        val base = ctx?.filesDir ?: return path
        return File(base, path).absolutePath
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
                val ratio = maxDimension.toFloat() / longSide
                val dw = (decoded.width * ratio).roundToInt().coerceAtLeast(1)
                val dh = (decoded.height * ratio).roundToInt().coerceAtLeast(1)
                return Lanczos3.scale(decoded, dw, dh)
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
        return try {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            draw(ctx, cfg, canvas, w.toFloat(), h.toFloat(), spec.designWidth, spec.designHeight)
            bitmap
        } catch (_: Throwable) {
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
        return try {
            outFile.parentFile?.mkdirs()
            val tmp = File(outFile.absolutePath + ".tmp")
            FileOutputStream(tmp).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.flush()
            }
            tmp.renameTo(outFile) || run {
                outFile.delete()
                tmp.renameTo(outFile)
            }
        } catch (_: Throwable) {
            false
        } finally {
            bitmap.recycle()
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
    ) {
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
        val image = FaceImageLoader.load(imagePath)
        if (image != null && !image.isRecycled && image.width > 0 && image.height > 0) {
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
                    Lanczos3.scale(image, dst.width(), dst.height())
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
            if (spec.glow && spec.glowRadius > 0f) {
                setShadowLayer(
                    spec.glowRadius * designScale,
                    0f,
                    0f,
                    withAlpha(spec.glowColor, stickerAlpha),
                )
            }
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
        var baseline = cy - totalHeight / 2f - fm.ascent
        canvas.save()
        canvas.rotate(FaceGeometry.clampRotation(spec.rot), cx, cy)
        for (line in lines) {
            // Outline first, then fill on top: the fill covers the inner half of the stroke so the
            // outline only shows on the glyph's outer edge (no lines inside the glyph).
            if (strokePaint != null) canvas.drawText(line, cx, baseline, strokePaint)
            canvas.drawText(line, cx, baseline, fillPaint)
            baseline += lineHeight
        }
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
        val image = FaceImageLoader.load(path, 1536) ?: return
        if (image.isRecycled || image.width <= 0) return
        val dstW = (spec.w.coerceIn(0.05f, 2f) * width).coerceAtLeast(8f)
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
            val alpha = (spec.alpha.coerceIn(0f, 1f) * 255f).toInt()
            if (alpha >= 255) {
                canvas.drawBitmap(image, Rect(0, 0, image.width, image.height), dst, bitmapPaint)
            } else {
                val paint = Paint(bitmapPaint).apply { this.alpha = alpha }
                canvas.drawBitmap(image, Rect(0, 0, image.width, image.height), dst, paint)
            }
        }
        canvas.restore()
    }

    /** Multiplies an ARGB colour's alpha by [factor] (0..1). */
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
 * Round 25: Lanczos3 resampler (a = 3, two-pass separable convolution).
 * Default working space is linear light (sRGB decode -> convolve -> sRGB encode), which avoids the
 * darkening/soft ring artifacts of naive sRGB resampling. Runs on the calling (background) thread.
 */
object Lanczos3 {

    private const val A = 3f
    private const val SRGB_GAMMA = 2.2f

    fun scale(src: Bitmap, dstWidth: Int, dstHeight: Int, linearLight: Boolean = true): Bitmap {
        if (dstWidth <= 0 || dstHeight <= 0) return src
        if (src.width == dstWidth && src.height == dstHeight) return src
        val sw = src.width
        val sh = src.height
        if (sw <= 0 || sh <= 0) return src
        val pixels = IntArray(sw * sh)
        src.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        val srcChannels = FloatArray(sw * sh * 4)
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = ((p ushr 24) and 0xFF) / 255f
            val r = ((p ushr 16) and 0xFF) / 255f
            val g = ((p ushr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            val base = i * 4
            if (linearLight) {
                srcChannels[base] = srgbToLinear(r)
                srcChannels[base + 1] = srgbToLinear(g)
                srcChannels[base + 2] = srgbToLinear(b)
            } else {
                srcChannels[base] = r
                srcChannels[base + 1] = g
                srcChannels[base + 2] = b
            }
            srcChannels[base + 3] = a
        }

        // Horizontal pass: source (sw x sh) -> temp (dstWidth x sh)
        val temp = FloatArray(dstWidth * sh * 4)
        val xScale = sw.toFloat() / dstWidth
        val xSupport = A * maxOf(1f, xScale)
        for (dx in 0 until dstWidth) {
            val center = (dx + 0.5f) * xScale - 0.5f
            val start = kotlin.math.floor(center - xSupport).toInt()
            val end = kotlin.math.ceil(center + xSupport).toInt()
            val weights = FloatArray(end - start + 1)
            var sum = 0f
            for (sx in start..end) {
                val w = kernel((sx - center) / xSupport)
                weights[sx - start] = w
                sum += w
            }
            if (sum != 0f) for (i in weights.indices) weights[i] /= sum
            for (y in 0 until sh) {
                var r = 0f
                var g = 0f
                var b = 0f
                var a = 0f
                for (sx in start..end) {
                    val clamped = sx.coerceIn(0, sw - 1)
                    val w = weights[sx - start]
                    val si = (y * sw + clamped) * 4
                    r += srcChannels[si] * w
                    g += srcChannels[si + 1] * w
                    b += srcChannels[si + 2] * w
                    a += srcChannels[si + 3] * w
                }
                val ti = (y * dstWidth + dx) * 4
                temp[ti] = r
                temp[ti + 1] = g
                temp[ti + 2] = b
                temp[ti + 3] = a
            }
        }

        // Vertical pass: temp (dstWidth x sh) -> out (dstWidth x dstHeight)
        val outChannels = FloatArray(dstWidth * dstHeight * 4)
        val yScale = sh.toFloat() / dstHeight
        val ySupport = A * maxOf(1f, yScale)
        for (dy in 0 until dstHeight) {
            val center = (dy + 0.5f) * yScale - 0.5f
            val start = kotlin.math.floor(center - ySupport).toInt()
            val end = kotlin.math.ceil(center + ySupport).toInt()
            val weights = FloatArray(end - start + 1)
            var sum = 0f
            for (sy in start..end) {
                val w = kernel((sy - center) / ySupport)
                weights[sy - start] = w
                sum += w
            }
            if (sum != 0f) for (i in weights.indices) weights[i] /= sum
            for (x in 0 until dstWidth) {
                var r = 0f
                var g = 0f
                var b = 0f
                var a = 0f
                for (sy in start..end) {
                    val clamped = sy.coerceIn(0, sh - 1)
                    val w = weights[sy - start]
                    val ti = (clamped * dstWidth + x) * 4
                    r += temp[ti] * w
                    g += temp[ti + 1] * w
                    b += temp[ti + 2] * w
                    a += temp[ti + 3] * w
                }
                val oi = (dy * dstWidth + x) * 4
                outChannels[oi] = r
                outChannels[oi + 1] = g
                outChannels[oi + 2] = b
                outChannels[oi + 3] = a
            }
        }

        val outPixels = IntArray(dstWidth * dstHeight)
        for (i in outPixels.indices) {
            val base = i * 4
            val r = outChannels[base].coerceIn(0f, 1f)
            val g = outChannels[base + 1].coerceIn(0f, 1f)
            val b = outChannels[base + 2].coerceIn(0f, 1f)
            val a = outChannels[base + 3].coerceIn(0f, 1f)
            val ri = if (linearLight) linearToSrgb(r) else r
            val gi = if (linearLight) linearToSrgb(g) else g
            val bi = if (linearLight) linearToSrgb(b) else b
            outPixels[i] = ((a * 255f + 0.5f).toInt() shl 24) or
                ((ri * 255f + 0.5f).toInt() shl 16) or
                ((gi * 255f + 0.5f).toInt() shl 8) or
                (bi * 255f + 0.5f).toInt()
        }
        val out = Bitmap.createBitmap(dstWidth, dstHeight, Bitmap.Config.ARGB_8888)
        out.setPixels(outPixels, 0, dstWidth, 0, 0, dstWidth, dstHeight)
        return out
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

    private fun srgbToLinear(c: Float): Float =
        if (c <= 0.04045f) {
            c / 12.92f
        } else {
            Math.pow(((c + 0.055f) / 1.055f).toDouble(), SRGB_GAMMA.toDouble()).toFloat()
        }

    private fun linearToSrgb(c: Float): Float =
        if (c <= 0.0031308f) {
            c * 12.92f
        } else {
            (1.055f * Math.pow(c.toDouble(), 1.0 / SRGB_GAMMA) - 0.055f).toFloat()
        }
}







