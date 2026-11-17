package com.cardpersonality.honor.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.cardpersonality.honor.BuildConfig
import com.cardpersonality.honor.FaceRenderer
import com.cardpersonality.honor.FaceTypefaces
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max

/**
 * Local store for 卡面工坊 (owned by ui-dev).
 *
 * Layout under `<filesDir>`:
 *  - config.json                      [ConfigStore] JSON (global version + cards + type defaults)
 *  - report.json                      cards reported by the hook: {"updatedAt":n,"cards":[...]}
 *  - exports/<sha256hex(key)>.jpg     final 2016x1248 JPEG served through the provider
 *  - images/img_*.jpg, stk_*.png      imported source images / image stickers
 *
 * Paths stored in JSON are relative to filesDir; runtime [CardFaceConfig] paths are absolute.
 * The hook/provider only needs: [storeVersion], [resolveConfig], [configJson], [cardVersion],
 * [exportFileFor], [ensureExport], [reportCards], [reportJson], [cards].
 */
object FaceRepository {

    const val AUTHORITY = "com.cardpersonality.honor.face"

    /** Broadcast action consumed by the hook inside the wallet process (v3+ live refresh). */
    const val ACTION_CONFIG_CHANGED = "com.cardpersonality.honor.CONFIG_CHANGED"

    const val WALLET_PACKAGE = "com.hihonor.wallet"

    const val CONFIG_FILE = "config.json"
    const val REPORT_FILE = "report.json"
    const val EXPORT_DIR = "exports"
    const val IMAGE_DIR = "images"

    /** Imported custom font files (.ttf/.otf) referenced by text stickers. */
    const val FONT_DIR = "fonts"

    /** Hook-captured original wallet face snapshots (written by FaceProvider "saveOriginalFace"). */
    const val ORIGINALS_DIR = "originals"

    /** Downloaded wallet CDN faces (from CardInfo.faceUrl) when no hook snapshot exists. */
    const val CACHE_ORIGINAL_DIR = "cache/original"

    private const val TAG = "CARD_PERSONALITY_IMPORT"

    /** Upper bound for an imported font file (32 MiB; large CJK fonts are commonly ~10 MiB). */
    private const val MAX_FONT_BYTES = 32L * 1024L * 1024L

    /** Bounds for a card ZIP import (our own export is tiny, this is just a safety net). */
    private const val MAX_ZIP_BYTES = 128L * 1024L * 1024L
    private const val MAX_ZIP_ENTRIES = 256

    /** Result of importing a user image: exactly one of [path]/[error] is set. */
    data class ImportResult(val path: String?, val error: String? = null) {
        val ok: Boolean get() = path != null
    }

    /** Final exported face: 3x of the 336x208dp wallet canvas. */
    const val EXPORT_WIDTH = FaceRenderer.EXPORT_WIDTH
    const val EXPORT_HEIGHT = FaceRenderer.EXPORT_HEIGHT

    @Volatile
    private var cachedStore: ConfigStore? = null

    @Volatile
    private var cachedStoreStamp: Long = -1L

    // ------------------------------------------------------------------ store IO

    fun storeVersion(ctx: Context): Int = store(ctx).version

    @Synchronized
    fun store(ctx: Context): ConfigStore {
        val file = configFile(ctx)
        val stamp = if (file.isFile) file.lastModified() else 0L
        val cached = cachedStore
        if (cached != null && stamp == cachedStoreStamp) return cached
        val parsed = if (file.isFile) {
            try {
                ConfigStore.fromJson(JSONObject(file.readText(Charsets.UTF_8)))
            } catch (_: Throwable) {
                ConfigStore()
            }
        } else {
            ConfigStore()
        }
        val resolved = parsed.copy(
            cards = parsed.cards.mapValues { (_, cfg) -> cfg.absolute(ctx) },
            types = parsed.types.mapValues { (_, cfg) -> cfg.absolute(ctx) },
        )
        cachedStore = resolved
        cachedStoreStamp = stamp
        return resolved
    }

    @Synchronized
    private fun writeStore(ctx: Context, store: ConfigStore) {
        val file = configFile(ctx)
        file.parentFile?.mkdirs()
        val stored = store.copy(
            cards = store.cards.mapValues { (_, cfg) -> cfg.relative(ctx) },
            types = store.types.mapValues { (_, cfg) -> cfg.relative(ctx) },
        )
        writeTextAtomically(file, stored.toJson().toString())
        cachedStore = store
        cachedStoreStamp = if (file.isFile) file.lastModified() else System.currentTimeMillis()
    }

    // ------------------------------------------------------------------ config resolution

    /** A config plus the storage key it was resolved from (export file name uses this key). */
    data class Resolved(
        val key: CardKey,
        val config: CardFaceConfig,
        /** Exact storage key including an optional wallet package prefix. */
        val fileKey: String = key.fileKey,
    )

    /**
     * Resolution order: exact card config with a custom image -> per-type default config with a
     * custom image -> exact/type config without image (elements only, used for 元素组件 toggles).
     */
    fun effective(
        ctx: Context,
        type: String,
        id: String,
        walletPackage: String? = null,
    ): Resolved? {
        val store = store(ctx)
        val wallet = WalletScope.normalize(walletPackage)
        val exactKey = CardKey(type, id)
        val scopedExactKey = WalletScope.configFileKey(exactKey, wallet)
        val exact = store.cards[scopedExactKey]
            ?: if (WalletScope.isHonor(wallet)) store.cards[exactKey.fileKey] else null
        val exactStorageKey = if (store.cards.containsKey(scopedExactKey)) scopedExactKey else exactKey.fileKey
        if (exact != null && exact.hasCustomFace) return Resolved(exactKey, exact, exactStorageKey)

        val typeKey = CardKey.typeDefault(type)
        val scopedTypeKey = WalletScope.configFileKey(typeKey, wallet)
        val typeCfg = store.types[scopedTypeKey]
            ?: if (WalletScope.isHonor(wallet)) store.types[type] else null
        val typeStorageKey = if (store.types.containsKey(scopedTypeKey)) scopedTypeKey else typeKey.fileKey
        if (typeCfg != null && typeCfg.hasCustomFace) return Resolved(typeKey, typeCfg, typeStorageKey)

        val fallback = exact ?: typeCfg
        return fallback?.let {
            Resolved(exactKey, it, if (exact != null) exactStorageKey else typeStorageKey)
        }
    }

    fun resolveConfig(
        ctx: Context,
        type: String,
        id: String,
        walletPackage: String? = null,
    ): CardFaceConfig? = effective(ctx, type, id, walletPackage)?.config

    fun hasCustomFace(
        ctx: Context,
        type: String,
        id: String,
        walletPackage: String? = null,
    ): Boolean = effective(ctx, type, id, walletPackage)?.config?.hasCustomFace == true

    /** Version of the config that actually feeds the wallet (exact or type default). */
    fun cardVersion(
        ctx: Context,
        type: String,
        id: String,
        walletPackage: String? = null,
    ): Int = effective(ctx, type, id, walletPackage)?.config?.version ?: 0

    /** Hidden wallet element ids = exact config ∪ type default config (wallet-scoped). */
    fun hiddenAppElements(
        ctx: Context,
        type: String,
        id: String,
        walletPackage: String? = null,
    ): List<String> {
        val store = store(ctx)
        val wallet = WalletScope.normalize(walletPackage)
        val scopedExact = WalletScope.configFileKey(CardKey(type, id), wallet)
        val scopedType = WalletScope.configFileKey(CardKey.typeDefault(type), wallet)
        val exact = (store.cards[scopedExact]?.hiddenAppElements.orEmpty() +
            if (WalletScope.isHonor(wallet)) store.cards[CardKey(type, id).fileKey]?.hiddenAppElements.orEmpty() else emptyList())
        val typeCfg = (store.types[scopedType]?.hiddenAppElements.orEmpty() +
            if (WalletScope.isHonor(wallet)) store.types[type]?.hiddenAppElements.orEmpty() else emptyList())
        return (exact + typeCfg).distinct()
    }

    fun loadCard(
        ctx: Context,
        key: CardKey,
        walletPackage: String? = null,
    ): CardFaceConfig? {
        val store = store(ctx)
        val wallet = WalletScope.normalize(walletPackage)
        val scoped = WalletScope.configFileKey(key, wallet)
        val legacy = if (WalletScope.isHonor(wallet)) key.fileKey else null
        return if (key.id == CardKey.TYPE_DEFAULT_ID) {
            store.types[scoped] ?: legacy?.let { store.types[it] }
        } else {
            store.cards[scoped] ?: legacy?.let { store.cards[it] } ?: store.types[scoped]
        }
    }

    // ------------------------------------------------------------------ saving

    /**
     * Persists one card (or type default) config:
     *  - bumps the global version and this card's version,
     *  - rewrites config.json atomically,
     *  - re-renders exports/<sha256(key)>.jpg.
     */
    @Synchronized
    fun saveCard(
        ctx: Context,
        key: CardKey,
        config: CardFaceConfig,
        walletPackage: String? = null,
    ): CardFaceConfig {
        val store = store(ctx)
        val wallet = WalletScope.normalize(walletPackage)
        val fileKey = WalletScope.configFileKey(key, wallet)
        val previous = if (key.id == CardKey.TYPE_DEFAULT_ID) {
            store.types[fileKey]
        } else {
            store.cards[fileKey]
        }
        val cardVersion = (previous?.version ?: 0) + 1
        val globalVersion = store.version + 1
        val now = System.currentTimeMillis()
        val hasFace = config.imagePath != null
        val saved = config.copy(
            version = cardVersion,
            updatedAt = now,
            hasCustomFace = hasFace,
        )
        val cards = HashMap(store.cards)
        val types = HashMap(store.types)
        if (key.id == CardKey.TYPE_DEFAULT_ID) {
            types[fileKey] = saved
        } else {
            cards[fileKey] = saved
        }
        writeStore(ctx, ConfigStore(globalVersion, now, cards, types))
        renderExport(ctx, key, wallet)
        pushToWalletBridge(ctx, key, wallet)
        OpLog.record(
            ctx,
            "config",
            if (hasFace) "save" else "save-elements",
            "$fileKey wallet=$wallet v=$cardVersion",
        )
        notifyWalletConfigChanged(ctx)
        // Hand the caller absolute paths back for UI state.
        return saved
    }

    @Synchronized
    fun clearCard(ctx: Context, key: CardKey, walletPackage: String? = null) {
        val store = store(ctx)
        val wallet = WalletScope.normalize(walletPackage)
        val fileKey = WalletScope.configFileKey(key, wallet)
        val cards = HashMap(store.cards)
        val types = HashMap(store.types)
        if (key.id == CardKey.TYPE_DEFAULT_ID) {
            types.remove(fileKey)
        } else {
            cards.remove(fileKey)
        }
        if (WalletScope.isHonor(wallet)) {
            // legacy unprefixed key (old configs)
            if (key.id == CardKey.TYPE_DEFAULT_ID) types.remove(key.type) else cards.remove(key.fileKey)
        }
        writeStore(ctx, store.copy(version = store.version + 1, updatedAt = System.currentTimeMillis(), cards = cards, types = types))
        exportFile(ctx, fileKey)?.delete()
        if (WalletScope.isHonor(wallet)) exportFile(ctx, key.fileKey)?.delete()
        notifyWalletConfigChanged(ctx)
        if (WalletScope.isOppo(wallet)) {
            try {
                com.cardpersonality.honor.data.bridge.PanelBridgeServer.ensureStarted(ctx)
                com.cardpersonality.honor.data.bridge.PanelBridgeServer.pushConfig(ctx, allConfigsJson(ctx))
                com.cardpersonality.honor.data.bridge.PanelBridgeServer.pushFaceDelete(ctx, fileKey)
            } catch (_: Throwable) {
            }
        }
        OpLog.record(ctx, "config", "clear", "$fileKey wallet=$wallet")
    }

    /**
     * Tells the hook inside the wallet process to re-read the config and refresh attached
     * card-face views; the hook registers a runtime receiver for this action (v3+).
     * Best effort: never let a broadcast failure break saving.
     */
    fun notifyWalletConfigChanged(ctx: Context) {
        OpLog.record(ctx, "wallet", "refresh", "CONFIG_CHANGED broadcast")
        try {
            val intent = android.content.Intent(ACTION_CONFIG_CHANGED)
                .setPackage(WALLET_PACKAGE)
            ctx.sendBroadcast(intent)
        } catch (_: Throwable) {
            // Wallet not installed / broadcast restricted: next wallet start picks the config up.
        }
        try {
            ctx.contentResolver.notifyChange(
                android.net.Uri.parse("content://$AUTHORITY/config"),
                null,
            )
        } catch (_: Throwable) {
            // ContentObserver channel unavailable: broadcast / next start still apply the config.
        }
    }

    // ------------------------------------------------------------------ exports

    fun exportFile(ctx: Context, fileKey: String): File =
        File(File(ctx.filesDir, EXPORT_DIR), Hashing.sha256Hex(fileKey) + ".jpg")

    /** Existing export file for the effective config, or null when the card has no custom face. */
    fun exportFileFor(
        ctx: Context,
        type: String,
        id: String,
        walletPackage: String? = null,
    ): File? {
        val resolved = effective(ctx, type, id, walletPackage) ?: return null
        if (!resolved.config.hasCustomFace) return null
        val file = exportFile(ctx, resolved.fileKey)
        return if (file.isFile) file else null
    }

    /** Ensures the exported JPEG exists (re-renders from config when missing). */
    @Synchronized
    fun ensureExport(
        ctx: Context,
        type: String,
        id: String,
        walletPackage: String? = null,
    ): File? {
        val resolved = effective(ctx, type, id, walletPackage) ?: return null
        if (!resolved.config.hasCustomFace) return null
        val file = exportFile(ctx, resolved.fileKey)
        if (file.isFile && file.length() > 0L) return file
        return if (renderExport(ctx, resolved.key, walletPackage)) file else null
    }

    @Synchronized
    fun renderExport(ctx: Context, key: CardKey, walletPackage: String? = null): Boolean {
        val store = store(ctx)
        val wallet = WalletScope.normalize(walletPackage)
        val fileKey = WalletScope.configFileKey(key, wallet)
        val cfg = if (key.id == CardKey.TYPE_DEFAULT_ID) {
            store.types[fileKey] ?: if (WalletScope.isHonor(wallet)) store.types[key.type] else null
        } else {
            store.cards[fileKey] ?: if (WalletScope.isHonor(wallet)) store.cards[key.fileKey] else null
        }
        if (cfg == null || !cfg.hasCustomFace) return false
        val out = exportFile(ctx, fileKey)
        val ok = FaceRenderer.renderToFile(ctx, cfg, out, walletPackage = wallet)
        OpLog.record(
            ctx,
            "render",
            if (ok) "export-ok" else "export-fail",
            "$fileKey wallet=$wallet ${out.length()}B",
        )
        return ok
    }

    // ------------------------------------------------------------------ provider helpers

    fun configJson(
        ctx: Context,
        type: String,
        id: String,
        walletPackage: String? = null,
    ): String {
        val resolved = effective(ctx, type, id, walletPackage) ?: return "{}"
        return resolved.config.toJson().toString()
    }

    fun allConfigsJson(ctx: Context): String = store(ctx).toJson().toString()

    // ------------------------------------------------------------------ reports

    @Synchronized
    fun reportCards(ctx: Context, json: String?): Boolean {
        if (json.isNullOrBlank()) return false
        val incoming = try {
            parseReport(json)
        } catch (_: Throwable) {
            return false
        }
        if (incoming.isEmpty()) return false
        val existing = cards(ctx).associateBy { it.scopedFileKey() }
        val merged = LinkedHashMap<String, CardInfo>()
        existing.forEach { (k, v) -> merged[k] = v }
        val now = System.currentTimeMillis()
        for (card in incoming) {
            val key = card.scopedFileKey()
            val old = merged[key]
            merged[key] = card.copy(
                lastSeen = if (card.lastSeen > 0L) card.lastSeen else now,
                appElementIds = if (card.appElementIds.isNotEmpty()) {
                    card.appElementIds
                } else {
                    old?.appElementIds.orEmpty()
                },
                faceUrl = card.faceUrl ?: old?.faceUrl,
            )
        }
        val obj = JSONObject().apply {
            put("updatedAt", now)
            val arr = JSONArray()
            merged.values.forEach { arr.put(it.toJson()) }
            put("cards", arr)
        }
        writeTextAtomically(reportFile(ctx), obj.toString())
        OpLog.record(ctx, "wallet", "report", "cards=${merged.size}")
        return true
    }

    fun cards(ctx: Context): List<CardInfo> {
        val file = reportFile(ctx)
        if (!file.isFile) return emptyList()
        return try {
            parseReport(file.readText(Charsets.UTF_8))
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun reportUpdatedAt(ctx: Context): Long {
        val file = reportFile(ctx)
        if (!file.isFile) return 0L
        return try {
            JSONObject(file.readText(Charsets.UTF_8)).optLong("updatedAt", 0L)
        } catch (_: Throwable) {
            0L
        }
    }

    fun reportJson(ctx: Context): String {
        val file = reportFile(ctx)
        if (file.isFile) {
            return try {
                file.readText(Charsets.UTF_8)
            } catch (_: Throwable) {
                EMPTY_REPORT
            }
        }
        return EMPTY_REPORT
    }

    private fun parseReport(json: String): List<CardInfo> {
        val trimmed = json.trim()
        if (trimmed.isEmpty()) return emptyList()
        return if (trimmed.startsWith("[")) {
            val arr = JSONArray(trimmed)
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { CardInfo.fromJson(it) } }
        } else {
            val obj = JSONObject(trimmed)
            val arr = obj.optJSONArray("cards") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { CardInfo.fromJson(it) } }
        }
    }

    // ------------------------------------------------------------------ image import

    /** Copies a picked photo into app files (downsampled JPEG) and returns its absolute path. */
    fun importSourceImage(ctx: Context, uri: Uri): String? =
        importSourceImageOutcome(ctx, uri).path

    /** Like [importSourceImage] but reports a user-facing failure reason. */
    fun importSourceImageOutcome(ctx: Context, uri: Uri): ImportResult =
        importBitmap(ctx, uri, maxDimension = 2560, png = false, prefix = "img")

    /** Copies a picked sticker image into app files (PNG, keeps alpha). */
    fun importStickerImage(ctx: Context, uri: Uri): String? =
        importStickerImageOutcome(ctx, uri).path

    /** Like [importStickerImage] but reports a user-facing failure reason. */
    fun importStickerImageOutcome(ctx: Context, uri: Uri): ImportResult =
        importBitmap(ctx, uri, maxDimension = 1024, png = true, prefix = "stk")

    /**
     * Copies the bundled demo card art (res/raw/sample_face.jpg) into files/images like a picked
     * photo, so the panel can demonstrate the full pick -> crop -> save flow on a device whose
     * gallery is empty.
     */
    fun importSampleImageOutcome(ctx: Context): ImportResult {
        return try {
            val resId = ctx.resources.getIdentifier("sample_face", "raw", ctx.packageName)
            if (resId == 0) return ImportResult(null, "内置示例图缺失")
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val bitmap = ctx.resources.openRawResource(resId).use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return ImportResult(null, "内置示例图解码失败")
            val path = writeBitmapToImages(ctx, bitmap, png = false, prefix = "img_sample")
            bitmap.recycle()
            return if (path != null) ImportResult(path) else ImportResult(null, "保存图片失败")
        } catch (t: Throwable) {
            Log.e(TAG, "sample import failed", t)
            ImportResult(null, friendlyImportError(t))
        }
    }

    fun importSampleImage(ctx: Context): String? = importSampleImageOutcome(ctx).path

    /**
     * Robust content-URI -> app-files import shared by every picker entry point.
     *
     * NOTE: with `inJustDecodeBounds = true` BitmapFactory.decodeStream() ALWAYS returns null;
     * the bounds are written into the Options. The old code treated that null as failure, which
     * made every gallery import fail — do not reintroduce that check.
     */
    private fun importBitmap(
        ctx: Context,
        uri: Uri,
        maxDimension: Int,
        png: Boolean,
        prefix: String,
    ): ImportResult {
        return try {
            val resolver = ctx.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val opened = resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
                true
            } ?: false
            if (!opened) {
                Log.e(TAG, "import failed uri=$uri: cannot open stream")
                return ImportResult(null, "无法读取所选文件")
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Log.e(TAG, "import failed uri=$uri: unsupported image (w=${bounds.outWidth}, h=${bounds.outHeight})")
                return ImportResult(null, "不支持的图片格式")
            }
            var sample = 1
            val longSide = max(bounds.outWidth, bounds.outHeight)
            while (longSide / (sample * 2) >= maxDimension) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: run {
                Log.e(TAG, "import failed uri=$uri: decode returned null (sample=$sample)")
                return ImportResult(null, "图片解码失败")
            }
            val path = writeBitmapToImages(ctx, bitmap, png = png, prefix = prefix)
            bitmap.recycle()
            if (path == null) {
                Log.e(TAG, "import failed uri=$uri: could not write output")
                ImportResult(null, "保存图片失败")
            } else {
                ImportResult(path)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "import failed uri=$uri", t)
            ImportResult(null, friendlyImportError(t))
        }
    }

    private fun writeBitmapToImages(
        ctx: Context,
        bitmap: Bitmap,
        png: Boolean,
        prefix: String,
    ): String? {
        return try {
            val dir = File(ctx.filesDir, IMAGE_DIR).apply { mkdirs() }
            val name = "${prefix}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}." +
                if (png) "png" else "jpg"
            val out = File(dir, name)
            FileOutputStream(out).use { stream ->
                if (png) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                } else {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
                }
                stream.flush()
            }
            if (out.isFile && out.length() > 0L) out.absolutePath else null
        } catch (t: Throwable) {
            Log.e(TAG, "write imported bitmap failed", t)
            null
        }
    }

    private fun friendlyImportError(t: Throwable): String = when (t) {
        is SecurityException -> "无读取权限（SecurityException）"
        is OutOfMemoryError -> "图片过大（内存不足）"
        is java.io.IOException -> {
            // Keep the concrete IO reason visible (resolver/stream errors are otherwise opaque).
            val detail = t.message?.take(80)?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
            "无法读取文件（IO：$detail）"
        }
        else -> t.javaClass.simpleName + (t.message?.let { "：" + it.take(80) } ?: "")
    }

    // ------------------------------------------------------------------ original wallet face

    /** Hook-captured wallet face snapshots (written by the provider). */
    fun originalsDir(ctx: Context): File = File(ctx.filesDir, ORIGINALS_DIR)

    /** Downloaded wallet CDN faces. */
    fun cacheOriginalsDir(ctx: Context): File = File(ctx.filesDir, CACHE_ORIGINAL_DIR)

    /**
     * Existing snapshot of the wallet's current (unmodified) face for this card:
     * hook-captured original first, then the downloaded CDN copy. Null for the type default.
     */
    fun originalSnapshotFile(ctx: Context, type: String, id: String): File? {
        if (id == CardKey.TYPE_DEFAULT_ID) return null
        val sha = Hashing.sha256Hex(CardKey(type, id).fileKey)
        val hooked = File(originalsDir(ctx), "$sha.jpg")
        if (hooked.isFile && hooked.length() > 0L) return hooked
        val cached = File(cacheOriginalsDir(ctx), "$sha.jpg")
        return if (cached.isFile && cached.length() > 0L) cached else null
    }

    /** Long side in pixels of an image file (0 when unreadable). */
    private fun longSideOf(file: File): Int {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            max(bounds.outWidth, bounds.outHeight).coerceAtLeast(0)
        } catch (_: Throwable) {
            0
        }
    }

    /**
     * Returns the local current-face snapshot, downloading [CardInfo.faceUrl] when absent.
     * Round 9: when a CDN URL exists we keep a NATIVE-resolution download and prefer whichever of
     * (hook snapshot / CDN download) is sharper, so "use current face as base" is not blurry.
     */
    fun ensureOriginalFace(ctx: Context, card: CardInfo): File? {
        val hooked = originalSnapshotFile(ctx, card.type, card.key.id)
        val url = card.faceUrl
        if (url.isNullOrBlank() || card.key.id == CardKey.TYPE_DEFAULT_ID) return hooked
        val sha = Hashing.sha256Hex(card.key.fileKey)
        val downloaded = File(cacheOriginalsDir(ctx), "$sha.jpg")
        if (!downloaded.isFile || downloaded.length() <= 0L) {
            try {
                downloadFaceToFile(url, downloaded)
            } catch (t: Throwable) {
                Log.w(TAG, "original face download failed url=$url", t)
            }
        }
        val hookSide = hooked?.let { longSideOf(it) } ?: 0
        val dlSide = if (downloaded.isFile) longSideOf(downloaded) else 0
        return when {
            dlSide > hookSide -> downloaded
            hooked != null -> hooked
            downloaded.isFile && downloaded.length() > 0L -> downloaded
            else -> null
        }
    }

    private fun downloadFaceToFile(url: String, target: File) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                instanceFollowRedirects = true
                requestMethod = "GET"
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                throw java.io.IOException("HTTP $code")
            }
            val bytes = connection.inputStream.use { stream ->
                val buffer = ByteArray(64 * 1024)
                val out = java.io.ByteArrayOutputStream()
                var total = 0
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > 16 * 1024 * 1024) throw java.io.IOException("face image too large")
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return
            // Round 9: keep the wallet-original face at native resolution (no 1024 px cap) so
            // "use current face as base" stays sharp; only very large images are downsampled.
            var sample = 1
            val longSide = max(bounds.outWidth, bounds.outHeight)
            while (longSide / (sample * 2) >= 4096) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return
            target.parentFile?.mkdirs()
            val tmp = File(target.absolutePath + ".tmp")
            FileOutputStream(tmp).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
                stream.flush()
            }
            bitmap.recycle()
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        } finally {
            try {
                connection?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    /** Copies the wallet's current face snapshot into files/images so it can be used as a source. */
    fun useOriginalAsSource(ctx: Context, type: String, id: String): ImportResult {
        // Round 9: prefer the sharpest original we have (native-res CDN copy may beat an old
        // low-resolution hook snapshot captured by earlier builds).
        val sha = Hashing.sha256Hex(CardKey(type, id).fileKey)
        val candidates = listOfNotNull(
            File(originalsDir(ctx), "$sha.jpg").takeIf { it.isFile && it.length() > 0L },
            File(cacheOriginalsDir(ctx), "$sha.jpg").takeIf { it.isFile && it.length() > 0L },
        ).sortedByDescending { longSideOf(it) }
        val snapshot = candidates.firstOrNull()
            ?: return ImportResult(null, "尚未获取到钱包当前卡面")
        return try {
            // Round-9b: copy the native snapshot BYTES into files/images (no decode/re-encode, no
            // resample) so a 672x416 wallet asset stays exactly 672x416 with its original quality.
            val dir = File(ctx.filesDir, IMAGE_DIR).apply { mkdirs() }
            val png = fileLooksLikePng(snapshot)
            val ext = if (png) "png" else "jpg"
            val target = File(
                dir,
                "img_current_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.$ext",
            )
            val tmp = File(target.absolutePath + ".tmp")
            snapshot.inputStream().use { input ->
                FileOutputStream(tmp).use { output -> input.copyTo(output) }
            }
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
            if (target.isFile && target.length() > 0L) {
                ImportResult(target.absolutePath)
            } else {
                ImportResult(null, "保存图片失败")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "use original as source failed", t)
            ImportResult(null, friendlyImportError(t))
        }
    }

    /** True when the file's first bytes are the PNG magic (snapshots may be PNG or JPEG). */
    private fun fileLooksLikePng(file: File): Boolean {
        return try {
            val head = ByteArray(8)
            file.inputStream().use { it.read(head) }
            head[0] == 0x89.toByte() && head[1] == 0x50.toByte() &&
                head[2] == 0x4E.toByte() && head[3] == 0x47.toByte()
        } catch (_: Throwable) {
            false
        }
    }

    /** Deletes the custom configuration for [key] so the wallet shows its original face again. */
    /** Round-22: reset/native-face-change hook for the UI: invalidate the cached original everywhere. */
    fun refreshOriginalSnapshots(ctx: Context, type: String, id: String) {
        try {
            com.cardpersonality.honor.hook.ProviderClient.refreshOriginal(type, id)
        } catch (_: Throwable) {
        }
        try {
            com.cardpersonality.honor.data.bridge.PanelBridgeServer.ensureStarted(ctx)
            for (wallet in WalletScope.OPPO_PACKAGES) {
                com.cardpersonality.honor.data.bridge.PanelBridgeServer.refreshOriginal(ctx, type, id)
            }
        } catch (_: Throwable) {
        }
        try {
            com.cardpersonality.honor.hook.FaceCapture.invalidate(type, id)
        } catch (_: Throwable) {
        }
    }

    fun restoreOriginalFace(ctx: Context, key: CardKey) {
        clearCard(ctx, key)
    }

    // ------------------------------------------------------------------ custom fonts

    fun fontsDir(ctx: Context): File = File(ctx.filesDir, FONT_DIR)

    /**
     * Copies a user-picked .ttf/.otf (OpenDocument content URI) into files/fonts and validates it
     * by loading it with Typeface.createFromFile. Returns the absolute path on success.
     */
    fun importFontOutcome(ctx: Context, uri: Uri): ImportResult {
        OpLog.record(ctx, "font", "import", uri.toString())
        var temp: File? = null
        return try {
            val resolver = ctx.contentResolver
            val extension = guessFontExtension(ctx, uri)
            val dir = fontsDir(ctx).apply { mkdirs() }
            val target = File(
                dir,
                "font_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.$extension",
            )
            temp = File(dir, target.name + ".tmp")
            var total = 0L
            var tooLarge = false
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > MAX_FONT_BYTES) {
                            tooLarge = true
                            break
                        }
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            } ?: return ImportResult(null, "无法读取字体文件")
            if (tooLarge) {
                temp.delete()
                return ImportResult(null, "字体文件过大（超过 32 MB）")
            }
            if (total <= 0L) {
                return ImportResult(null, "字体文件为空")
            }
            // Validate before publishing: a corrupt file must not break the renderer later.
            val typeface = runCatching { android.graphics.Typeface.createFromFile(temp) }.getOrNull()
                ?: run {
                    temp.delete()
                    return ImportResult(null, "不是有效的字体文件")
                }
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) {
                    temp.delete()
                    return ImportResult(null, "保存字体失败")
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                @Suppress("UNUSED_VARIABLE")
                val ignore = typeface
            }
            FaceTypefaces.invalidate()
            ImportResult(target.absolutePath)
        } catch (t: Throwable) {
            Log.e(TAG, "font import failed uri=$uri", t)
            try {
                temp?.delete()
            } catch (_: Throwable) {
            }
            ImportResult(null, friendlyImportError(t))
        }
    }

    fun importFont(ctx: Context, uri: Uri): String? = importFontOutcome(ctx, uri).path

    // ------------------------------------------------------------------ ZIP export

    /** Sub-directory under the public Download collection. */
    const val DOWNLOAD_EXPORT_DIR = "honor_cards_personality"

    /**
     * Exports the current card customization as a ZIP into
     * `Download/honor_cards_personality/` (MediaStore, API 29+) with a filesystem fallback.
     *
     * Contents: `manifest.txt`, `config.json` (relative paths), `source/bg.*`, `images/`
     * (sticker images), `fonts/` (custom font files). Returns the user-visible path on success.
     */
    fun exportCardZip(
        ctx: Context,
        key: CardKey,
        cfg: CardFaceConfig,
        title: String,
    ): ImportResult {
        OpLog.record(ctx, "zip", "export", "key=${key.fileKey} title=$title")
        return try {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val safeTitle = title.ifBlank { key.fileKey }
                .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
                .take(40)
                .ifBlank { "card" }
            val fileName = "${safeTitle}_$stamp.zip"

            val files = LinkedHashMap<String, File>()
            val source = cfg.imagePath?.let { File(it) }?.takeIf { it.isFile }
            if (source != null) {
                val ext = source.name.substringAfterLast('.', "jpg").lowercase()
                files["source/bg.$ext"] = source
            }
            cfg.stickers.mapNotNull { it.imagePath }.forEach { p ->
                val f = File(p)
                if (f.isFile) files["images/${f.name}"] = f
            }
            cfg.stickers.mapNotNull { it.customFontPath }.distinct().forEach { p ->
                val f = File(p)
                if (f.isFile) files["fonts/${f.name}"] = f
            }
            val configJson = cfg.relative(ctx).toJson().toString(2)
            val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZZ", Locale.US).format(Date())
            val manifest = buildString {
                appendLine("honor_cards_personality export")
                appendLine("appVersion=${com.cardpersonality.honor.BuildConfig.VERSION_NAME}")
                appendLine("exportedAt=$timestamp")
                appendLine("cardKey=${key.fileKey}")
                appendLine("cardTitle=$title")
                appendLine("sourceImage=${cfg.imagePath?.let { File(it).name } ?: "-"}")
                appendLine("stickerCount=${cfg.stickers.size}")
                appendLine("configVersion=${cfg.version}")
                appendLine("files:")
                appendLine("  manifest.txt")
                appendLine("  config.json")
                files.keys.forEach { appendLine("  $it") }
            }
            writeZip(ctx, fileName, files, configJson, manifest)
        } catch (t: Throwable) {
            Log.e(TAG, "zip export failed key=${key.fileKey}", t)
            ImportResult(null, "导出失败：" + friendlyImportError(t))
        }
    }

    private fun writeZip(
        ctx: Context,
        fileName: String,
        files: Map<String, File>,
        configJson: String,
        manifest: String,
    ): ImportResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/$DOWNLOAD_EXPORT_DIR")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        writeZipStream(out, files, configJson, manifest)
                    } ?: throw java.io.IOException("无法打开输出流")
                    val done = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    resolver.update(uri, done, null, null)
                    return ImportResult("Download/$DOWNLOAD_EXPORT_DIR/$fileName")
                } catch (t: Throwable) {
                    Log.e(TAG, "MediaStore zip write failed, trying app external dir", t)
                    runCatching { resolver.delete(uri, null, null) }
                }
            }
        }
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "exports").apply { mkdirs() }
        val out = File(dir, fileName)
        out.outputStream().use { writeZipStream(it, files, configJson, manifest) }
        return ImportResult(out.absolutePath)
    }

    private fun writeZipStream(
        out: java.io.OutputStream,
        files: Map<String, File>,
        configJson: String,
        manifest: String,
    ) {
        ZipOutputStream(out.buffered()).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            put("manifest.txt", manifest.toByteArray(Charsets.UTF_8))
            put("config.json", configJson.toByteArray(Charsets.UTF_8))
            files.forEach { (name, file) ->
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    // ------------------------------------------------------------------ ZIP import

    /** Result of [importCardZip]: the imported config (absolute runtime paths) or an error. */
    data class CardZipImport(val config: CardFaceConfig?, val error: String? = null) {
        val ok: Boolean get() = config != null
    }

    /**
     * Imports a card ZIP produced by [exportCardZip] into the app's own storage.
     *
     * Reads `manifest.txt`, `config.json` and the `source/`, `images/`, `fonts/` folders; copies every
     * referenced asset into `files/images` / `files/fonts` under collision-free names, rewrites the
     * config paths and returns the config with absolute runtime paths (the caller saves it).
     */
    fun importCardZip(ctx: Context, uri: Uri): CardZipImport {
        OpLog.record(ctx, "zip", "import", uri.toString())
        val resolver = ctx.contentResolver
        val tempRoot = File(ctx.cacheDir, "zip_import_" + UUID.randomUUID().toString().take(8))
        return try {
            tempRoot.mkdirs()
            val rootCanonical = tempRoot.canonicalFile
            var entryCount = 0
            var total = 0L
            val opened = resolver.openInputStream(uri)?.use { input ->
                java.util.zip.ZipInputStream(java.io.BufferedInputStream(input)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) {
                            zip.closeEntry()
                            continue
                        }
                        val name = entry.name.replace('\\', '/').trimStart('/')
                        if (name.isEmpty() || name.contains("..")) {
                            return CardZipImport(null, "压缩包路径非法")
                        }
                        entryCount++
                        if (entryCount > MAX_ZIP_ENTRIES) {
                            return CardZipImport(null, "压缩包文件过多（最多 $MAX_ZIP_ENTRIES 个）")
                        }
                        val target = File(tempRoot, name)
                        val canonical = target.canonicalFile
                        if (!canonical.path.startsWith(rootCanonical.path + File.separator)) {
                            return CardZipImport(null, "压缩包路径非法")
                        }
                        canonical.parentFile?.mkdirs()
                        canonical.outputStream().use { os ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = zip.read(buffer)
                                if (read <= 0) break
                                total += read
                                if (total > MAX_ZIP_BYTES) {
                                    return CardZipImport(null, "压缩包过大（最大 128MB）")
                                }
                                os.write(buffer, 0, read)
                            }
                        }
                        zip.closeEntry()
                    }
                }
                true
            } ?: false
            if (!opened) return CardZipImport(null, "无法读取所选文件")

            val configFile = File(tempRoot, CONFIG_FILE).takeIf { it.isFile }
                ?: tempRoot.walkTopDown()
                    .maxDepth(2)
                    .firstOrNull { it.isFile && it.name == CONFIG_FILE }
                ?: return CardZipImport(null, "压缩包缺少 config.json（不是本模块导出的卡面包）")
            val json = runCatching { JSONObject(configFile.readText(Charsets.UTF_8)) }.getOrNull()
                ?: return CardZipImport(null, "config.json 解析失败")
            val raw = runCatching { CardFaceConfig.fromJson(json) }.getOrNull()
                ?: return CardZipImport(null, "config.json 内容无效")

            val imagesDir = File(ctx.filesDir, IMAGE_DIR).apply { mkdirs() }
            val fontsDir = fontsDir(ctx).apply { mkdirs() }
            val stamp = UUID.randomUUID().toString().take(6)
            val copied = HashMap<String, String>()

            fun copyAsset(entryPath: String?, targetDir: File, folder: String): String? {
                if (entryPath.isNullOrEmpty()) return null
                copied[entryPath]?.let { return it }
                val base = entryPath.substringAfterLast('/').ifBlank { "asset" }
                val candidates = listOf(
                    File(tempRoot, entryPath),
                    File(tempRoot, "source/$base"),
                    File(tempRoot, "images/$base"),
                    File(tempRoot, "fonts/$base"),
                )
                val source = candidates.firstOrNull { it.isFile } ?: return null
                val safeBase = base.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
                val target = File(targetDir, "zip_${stamp}_$safeBase")
                source.copyTo(target, overwrite = true)
                val rel = "$folder/${target.name}"
                copied[entryPath] = rel
                return rel
            }

            // Source image: config path first, then our export's renamed source/bg.* entry.
            var newImagePath = copyAsset(raw.imagePath, imagesDir, IMAGE_DIR)
            if (newImagePath == null) {
                val sourceEntry = File(tempRoot, "source").listFiles()
                    ?.filter { it.isFile }
                    ?.firstOrNull()
                if (sourceEntry != null) {
                    val target = File(imagesDir, "zip_${stamp}_${sourceEntry.name}")
                    sourceEntry.copyTo(target, overwrite = true)
                    newImagePath = "$IMAGE_DIR/${target.name}"
                }
            }

            if (raw.imagePath != null && newImagePath == null) {
                return CardZipImport(null, "压缩包缺少源图（source/）")
            }

            var missingStickerAssets = 0
            val stickers = raw.stickers.mapNotNull { spec ->
                when {
                    spec.kind == StickerKind.IMAGE && !spec.imagePath.isNullOrEmpty() -> {
                        val imported = copyAsset(spec.imagePath, imagesDir, IMAGE_DIR)
                        if (imported == null) {
                            missingStickerAssets++
                            null
                        } else {
                            spec.copy(imagePath = imported)
                        }
                    }
                    else -> spec
                }
            }.map { spec ->
                if (!spec.customFontPath.isNullOrEmpty()) {
                    val imported = copyAsset(spec.customFontPath, fontsDir, FONT_DIR)
                    if (imported != null) spec.copy(customFontPath = imported) else spec
                } else {
                    spec
                }
            }
            if (missingStickerAssets > 0) {
                return CardZipImport(null, "压缩包缺少 $missingStickerAssets 个贴纸图片（images/）")
            }

            val relativeCfg = raw.copy(
                imagePath = newImagePath,
                stickers = stickers,
            ).relative(ctx)
            CardZipImport(relativeCfg.absolute(ctx))
        } catch (t: Throwable) {
            Log.e(TAG, "zip import failed uri=$uri", t)
            CardZipImport(null, "导入失败：" + friendlyImportError(t))
        } finally {
            runCatching { tempRoot.deleteRecursively() }
        }
    }

    private fun guessFontExtension(ctx: Context, uri: Uri): String {
        val name = runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else {
                    null
                }
            }
        }.getOrNull()
        val lower = name?.lowercase().orEmpty()
        return when {
            lower.endsWith(".otf") -> "otf"
            else -> "ttf"
        }
    }

    // ------------------------------------------------------------------ paths / serialization helpers

    fun configFile(ctx: Context): File = File(ctx.filesDir, CONFIG_FILE)

    fun reportFile(ctx: Context): File = File(ctx.filesDir, REPORT_FILE)

    fun toRelative(ctx: Context, absolutePath: String?): String? {
        if (absolutePath.isNullOrEmpty()) return null
        val root = ctx.filesDir.absolutePath.trimEnd('/') + "/"
        return if (absolutePath.startsWith(root)) absolutePath.substring(root.length) else absolutePath
    }

    fun toAbsolute(ctx: Context, storedPath: String?): String? {
        if (storedPath.isNullOrEmpty()) return null
        if (storedPath.startsWith("/")) return storedPath
        return File(ctx.filesDir, storedPath).absolutePath
    }

    private fun CardFaceConfig.absolute(ctx: Context): CardFaceConfig = copy(
        imagePath = toAbsolute(ctx, imagePath),
        stickers = stickers.map {
            it.copy(
                imagePath = toAbsolute(ctx, it.imagePath),
                customFontPath = toAbsolute(ctx, it.customFontPath),
            )
        },
    )

    private fun CardFaceConfig.relative(ctx: Context): CardFaceConfig = copy(
        imagePath = toRelative(ctx, imagePath),
        stickers = stickers.map {
            it.copy(
                imagePath = toRelative(ctx, it.imagePath),
                customFontPath = toRelative(ctx, it.customFontPath),
            )
        },
    )

    /**
     * Round-18: OPPO cards are not served through the ContentProvider (the OPPO wallet cannot
     * resolve it). Push the full scoped config + our rendered JPEG through the loopback bridge so
     * the wallet can replace the URL argument with the local file:// face.
     */
    private fun pushToWalletBridge(ctx: Context, key: CardKey, walletPackage: String?) {
        val wallet = WalletScope.normalize(walletPackage)
        if (!WalletScope.isOppo(wallet)) return
        try {
            val scoped = WalletScope.configFileKey(key, wallet)
            val export = exportFile(ctx, scoped)
            val cfg = loadCard(ctx, key, wallet) ?: effective(ctx, key.type, key.id, wallet)?.config
            if (cfg == null || !cfg.hasCustomFace || !export.isFile) return
            com.cardpersonality.honor.data.bridge.PanelBridgeServer.ensureStarted(ctx)
            val configJson = allConfigsJson(ctx)
            com.cardpersonality.honor.data.bridge.PanelBridgeServer.pushConfig(ctx, configJson)
            val keys = runCatching { org.json.JSONObject(configJson).optJSONObject("cards")?.length() ?: 0 }.getOrDefault(0)
            OpLog.record(ctx, "bridge", "push-config", "wallet=$wallet keys=$keys")
            // Round-23: the wallet's original face URL lets it match by exact source URL even when
            // the reported identity differs from the rendered NFC identity (friend-module approach).
            val faceUrl = try {
                cards(ctx).firstOrNull {
                    it.key.type == key.type && it.key.id == key.id && it.walletPackage == wallet
                }?.faceUrl
            } catch (_: Throwable) {
                null
            }
            com.cardpersonality.honor.data.bridge.PanelBridgeServer.pushFace(
                ctx,
                scoped,
                cfg.version,
                export.readBytes(),
                faceUrl,
            )
            OpLog.record(ctx, "bridge", "push-face", "key=$scoped wallet=$wallet v=${cfg.version}")
        } catch (_: Throwable) {
        }
    }

    private fun writeTextAtomically(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.absolutePath + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    internal const val EMPTY_REPORT = "{\"updatedAt\":0,\"cards\":[]}"
}


