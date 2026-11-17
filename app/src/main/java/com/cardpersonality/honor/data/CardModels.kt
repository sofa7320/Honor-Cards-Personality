package com.cardpersonality.honor.data

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * 卡面工坊 — shared configuration model (owned by ui-dev).
 *
 * Dependency-light on purpose: the hook running inside com.hihonor.wallet uses the same
 * classes/JSON helpers (only android platform classes + org.json), never Compose.
 */

/** Logical card families. String values are part of the on-disk format and content:// URIs. */
object CardTypes {
    const val TRANSIT = "transit"
    const val BANK = "bank"
    const val ACCESS = "access"
    const val CAMPUS = "campus"
    const val CARKEY = "carkey"
    const val EBIKE = "ebike"

    val ALL: List<String> = listOf(TRANSIT, ACCESS, CAMPUS, CARKEY, BANK, EBIKE)

    fun displayName(type: String): String = when (type) {
        TRANSIT -> "交通卡"
        BANK -> "银行卡"
        ACCESS -> "门禁卡"
        CAMPUS -> "校园卡"
        CARKEY -> "车钥匙"
        EBIKE -> "电动车钥匙"
        else -> "其他"
    }
}

/** Stable identity of one installed card: family + per-card identity from the hook. */
data class CardKey(val type: String, val id: String) {

    /** Storage key used inside config.json: "<type>:<identity>" or "type:<type>". */
    val fileKey: String
        get() = if (id == TYPE_DEFAULT_ID) "type:$type" else "$type:" + sanitizeIdentity(id)

    override fun toString(): String = fileKey

    companion object {
        const val TYPE_DEFAULT_ID = "*"

        fun typeDefault(type: String): CardKey = CardKey(type, TYPE_DEFAULT_ID)

        fun fromFileKey(fileKey: String): CardKey? {
            val idx = fileKey.indexOf(':')
            if (idx <= 0 || idx >= fileKey.length - 1) return null
            val type = fileKey.substring(0, idx)
            val rest = fileKey.substring(idx + 1)
            return if (fileKey.startsWith("type:")) {
                CardKey(rest, TYPE_DEFAULT_ID)
            } else {
                CardKey(type, rest)
            }
        }

        /** Filesystem/URI-safe identity. Long or exotic identities are hashed deterministically. */
        fun sanitizeIdentity(id: String): String {
            if (id == TYPE_DEFAULT_ID) return TYPE_DEFAULT_ID
            val safe = id.replace(SLUG_UNSAFE, "_")
            if (safe.length <= 48 && safe.isNotEmpty()) return safe
            return "h_" + Hashing.sha256Hex(id).take(24)
        }

        private val SLUG_UNSAFE = Regex("[^A-Za-z0-9._-]")
    }
}

/** SHA-256 helpers shared by storage key derivation and export file naming. */
object Hashing {
    fun sha256Hex(text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}

/** A card detected by the hook and reported to the app. */
data class CardInfo(
    val key: CardKey,
    val name: String,
    val subtitle: String = "",
    val lastSeen: Long = 0L,
    /** Wallet-drawn overlay element ids detected for this card type (requirement 3). */
    val appElementIds: List<String> = emptyList(),
    /**
     * Remote URL of the wallet's current card face (CDN), reported by the hook when available.
     * The panel downloads + caches it (files/cache/original/<sha(key)>.jpg) to show the current
     * face for cards without a custom one. Null = no URL / not reported.
     */
    val faceUrl: String? = null,
    /** Round-10: wallet package that reported this card (legacy reports default to Honor). */
    val walletPackage: String = WalletScope.HONOR,
    /** Round-10: "honor"/"oppo" (derived from [walletPackage] when the report omits it). */
    val walletKind: String = WalletScope.kindOf(WalletScope.HONOR),
    /**
     * Round-14: card balance text reported by some wallets (OPPO NfcCard getAcctAmount/getBalance,
     * e.g. "78.60"); empty when unknown. Display-only, never used for face matching.
     */
    val balance: String = "",
    /** Round-25: wallet-native face version key (Honor access faceId), null when unknown. */
    val nativeFaceKey: String? = null,
) {
    val type: String get() = key.type

    /** Wallet-scoped storage key: legacy for Honor, "<pkg>|<type>:<identity>" otherwise. */
    fun scopedFileKey(): String = WalletScope.configFileKey(key, walletPackage)

    fun toJson(): JSONObject = JSONObject().apply {
        put("type", key.type)
        put("identity", key.id)
        put("name", name)
        put("subtitle", subtitle)
        put("lastSeen", lastSeen)
        put("appElementIds", JSONArray(appElementIds))
        if (faceUrl != null) put("faceUrl", faceUrl) else put("faceUrl", JSONObject.NULL)
        put("wallet", walletPackage)
        put("walletKind", walletKind)
        if (balance.isNotEmpty()) put("balance", balance)
        if (!nativeFaceKey.isNullOrBlank()) put("nativeFaceKey", nativeFaceKey)
    }

    companion object {
        fun fromJson(o: JSONObject): CardInfo {
            val type = o.optString("type", CardTypes.TRANSIT)
            val identity = o.optString(
                "identity",
                o.optString("id", ""),
            )
            val wallet = o.optString("wallet", "").ifBlank { WalletScope.HONOR }
            val walletKind = o.optString("walletKind", "").ifBlank { WalletScope.kindOf(wallet) }
            return CardInfo(
                key = CardKey(type, identity),
                name = o.optString("name", ""),
                subtitle = o.optString("subtitle", ""),
                lastSeen = o.optLong("lastSeen", 0L),
                appElementIds = o.optJSONArray("appElementIds").toStringList(),
                faceUrl = o.optString("faceUrl", "").ifEmpty { null }.takeUnless { it == "null" },
                walletPackage = wallet,
                walletKind = walletKind,
                balance = o.optString("balance", ""),
                nativeFaceKey = o.optString("nativeFaceKey", "").ifEmpty { null },
            )
        }
    }
}

/** Kind of a user element component. */
object StickerKind {
    const val TEXT = "text"
    const val IMAGE = "image"
}

/**
 * One user element component ("元素组件") composited above the card face.
 * Positions/sizes are normalized to the face canvas (0..1), center-anchored.
 */
data class StickerSpec(
    val id: String,
    val kind: String = StickerKind.TEXT,
    val text: String = "",
    /** ARGB color; -1 = default white. */
    val color: Int = -1,
    /** Text size in card design units (20 = default); the renderer scales by canvas width / 336. */
    val sizeDp: Float = 20f,
    /** Absolute path of an imported sticker image (kind == IMAGE). */
    val imagePath: String? = null,
    val cx: Float = 0.5f,
    val cy: Float = 0.5f,
    /** Width as a fraction of the card width (IMAGE only). */
    val w: Float = 0.4f,
    /** Height fraction, informational for IMAGE hit-testing. */
    val h: Float = 0.2f,
    /** Degrees, clamped by the renderer to +/-15. */
    val rot: Float = 0f,
    val visible: Boolean = true,
    /** Opacity 0..1 applied to the whole sticker (text/image) in preview and export. */
    val alpha: Float = 1f,
    /** Optional user-visible name; null/blank = auto label (text content / "图片贴纸"). */
    val name: String? = null,

    // ---------------------------------------------------------------- text style (TEXT only)
    /** 100..900, default 700 (bold) to match the previous look. */
    val fontWeight: Int = 700,
    val italic: Boolean = false,
    val underline: Boolean = false,
    /** Glow (blurred shadow behind the glyphs). */
    val glow: Boolean = false,
    val glowColor: Int = 0xFF3482FF.toInt(),
    /** Blur radius in design units (20 = default); scales with canvas width. */
    val glowRadius: Float = 8f,
    /** Outline stroke drawn before the fill. */
    val stroke: Boolean = false,
    val strokeColor: Int = 0xFF000000.toInt(),
    /** Stroke width in design units (20 = default); scales with canvas width. */
    val strokeWidth: Float = 2f,
    /**
     * Built-in Android font family: "sans-serif", "sans-serif-medium", "sans-serif-condensed",
     * "sans-serif-black", "serif", "monospace", "cursive", "casual". Null/empty = default.
     */
    val fontFamily: String? = null,
    /** Absolute path of an imported .ttf/.otf in files/fonts (runtime; relative on disk). */
    val customFontPath: String? = null,

    // ---------------------------------------------------------------- flip (IMAGE only)
    val flipX: Boolean = false,
    val flipY: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("kind", kind)
        put("text", text)
        put("color", color)
        put("sizeDp", sizeDp.toDouble())
        if (imagePath != null) put("imagePath", imagePath) else put("imagePath", JSONObject.NULL)
        put("cx", cx.toDouble())
        put("cy", cy.toDouble())
        put("w", w.toDouble())
        put("h", h.toDouble())
        put("rot", rot.toDouble())
        put("visible", visible)
        put("alpha", alpha.toDouble())
        if (name != null) put("name", name) else put("name", JSONObject.NULL)
        put("fontWeight", fontWeight)
        put("italic", italic)
        put("underline", underline)
        put("glow", glow)
        put("glowColor", glowColor)
        put("glowRadius", glowRadius.toDouble())
        put("stroke", stroke)
        put("strokeColor", strokeColor)
        put("strokeWidth", strokeWidth.toDouble())
        if (fontFamily != null) put("fontFamily", fontFamily) else put("fontFamily", JSONObject.NULL)
        if (customFontPath != null) put("customFontPath", customFontPath) else put("customFontPath", JSONObject.NULL)
        put("flipX", flipX)
        put("flipY", flipY)
    }

    companion object {
        fun fromJson(o: JSONObject): StickerSpec = StickerSpec(
            id = o.optString("id", "s" + System.nanoTime()),
            kind = o.optString("kind", StickerKind.TEXT),
            text = o.optString("text", ""),
            color = o.optInt("color", -1),
            sizeDp = o.optDouble("sizeDp", 20.0).toFloat(),
            imagePath = o.optString("imagePath", "").ifEmpty { null }.takeUnless { it == "null" },
            cx = o.optDouble("cx", 0.5).toFloat(),
            cy = o.optDouble("cy", 0.5).toFloat(),
            w = o.optDouble("w", 0.4).toFloat(),
            h = o.optDouble("h", 0.2).toFloat(),
            rot = o.optDouble("rot", 0.0).toFloat(),
            visible = o.optBoolean("visible", true),
            alpha = o.optDouble("alpha", 1.0).toFloat().coerceIn(0f, 1f),
            name = o.optString("name", "").ifEmpty { null }.takeUnless { it == "null" },
            fontWeight = o.optInt("fontWeight", 700).coerceIn(100, 900),
            italic = o.optBoolean("italic", false),
            underline = o.optBoolean("underline", false),
            glow = o.optBoolean("glow", false),
            glowColor = o.optInt("glowColor", 0xFF3482FF.toInt()),
            glowRadius = o.optDouble("glowRadius", 8.0).toFloat(),
            stroke = o.optBoolean("stroke", false),
            strokeColor = o.optInt("strokeColor", 0xFF000000.toInt()),
            strokeWidth = o.optDouble("strokeWidth", 2.0).toFloat(),
            fontFamily = o.optString("fontFamily", "").ifEmpty { null }.takeUnless { it == "null" },
            customFontPath = o.optString("customFontPath", "").ifEmpty { null }.takeUnless { it == "null" },
            flipX = o.optBoolean("flipX", false),
            flipY = o.optBoolean("flipY", false),
        )
    }
}

/**
 * Per-card customization: source-image crop/zoom + user stickers + hidden wallet elements.
 * [version] is bumped on every save and used as `?v=` cache-buster on the content URI.
 */
data class CardFaceConfig(
    val version: Int = 0,
    /** Absolute path at runtime; stored relative to filesDir in config.json. */
    val imagePath: String? = null,
    /** Relative to the cover scale: 1 = cover, <1 = zoom out (letterbox), >1 = zoom in. */
    val scale: Float = 1f,
    /** Normalized pan of the image inside the canvas, 0 = centered. */
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    /** Degrees (reserved, currently unused for the source image). */
    val rotate: Float = 0f,
    /** Mirror the source image horizontally / vertically inside the card canvas. */
    val flipX: Boolean = false,
    val flipY: Boolean = false,
    val stickers: List<StickerSpec> = emptyList(),
    /** Wallet-drawn overlay element ids to hide; everything else is kept untouched. */
    val hiddenAppElements: List<String> = emptyList(),
    val updatedAt: Long = 0L,
    val hasCustomFace: Boolean = imagePath != null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("version", version)
        if (imagePath != null) put("imagePath", imagePath) else put("imagePath", JSONObject.NULL)
        put("scale", scale.toDouble())
        put("offsetX", offsetX.toDouble())
        put("offsetY", offsetY.toDouble())
        put("rotate", rotate.toDouble())
        put("flipX", flipX)
        put("flipY", flipY)
        val arr = JSONArray()
        stickers.forEach { arr.put(it.toJson()) }
        put("stickers", arr)
        put("hiddenAppElements", JSONArray(hiddenAppElements))
        put("updatedAt", updatedAt)
        put("hasCustomFace", hasCustomFace)
    }

    fun isEmptyCustomization(): Boolean =
        imagePath == null && stickers.isEmpty() && hiddenAppElements.isEmpty()

    companion object {
        val EMPTY = CardFaceConfig()

        fun fromJson(o: JSONObject): CardFaceConfig {
            val stickers = ArrayList<StickerSpec>()
            val arr = o.optJSONArray("stickers")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    stickers.add(StickerSpec.fromJson(item))
                }
            }
            val imagePath = o.optString("imagePath", "").ifEmpty { null }.takeUnless { it == "null" }
            return CardFaceConfig(
                version = o.optInt("version", 0),
                imagePath = imagePath,
                scale = o.optDouble("scale", 1.0).toFloat(),
                offsetX = o.optDouble("offsetX", 0.0).toFloat(),
                offsetY = o.optDouble("offsetY", 0.0).toFloat(),
                rotate = o.optDouble("rotate", 0.0).toFloat(),
                flipX = o.optBoolean("flipX", false),
                flipY = o.optBoolean("flipY", false),
                stickers = stickers,
                hiddenAppElements = o.optJSONArray("hiddenAppElements").toStringList(),
                updatedAt = o.optLong("updatedAt", 0L),
                hasCustomFace = o.optBoolean("hasCustomFace", imagePath != null),
            )
        }
    }
}

/**
 * Whole on-disk store: `<filesDir>/config.json`
 * `{"version":N,"updatedAt":ms,"cards":{"<type>:<identity>":{...}},"types":{"<type>":{...}}}`
 * A single global [version] is bumped on every save (hook cache-busting) together with the
 * per-card config version.
 */
data class ConfigStore(
    val version: Int = 0,
    val updatedAt: Long = 0L,
    val cards: Map<String, CardFaceConfig> = emptyMap(),
    val types: Map<String, CardFaceConfig> = emptyMap(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("version", version)
        put("updatedAt", updatedAt)
        val cardsObj = JSONObject()
        cards.forEach { (k, v) -> cardsObj.put(k, v.toJson()) }
        put("cards", cardsObj)
        val typesObj = JSONObject()
        types.forEach { (k, v) -> typesObj.put(k, v.toJson()) }
        put("types", typesObj)
    }

    companion object {
        fun fromJson(o: JSONObject): ConfigStore {
            val cards = HashMap<String, CardFaceConfig>()
            o.optJSONObject("cards")?.let { cardsObj ->
                val keys = cardsObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    cardsObj.optJSONObject(k)?.let { cards[k] = CardFaceConfig.fromJson(it) }
                }
            }
            val types = HashMap<String, CardFaceConfig>()
            o.optJSONObject("types")?.let { typesObj ->
                val keys = typesObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    typesObj.optJSONObject(k)?.let { types[k] = CardFaceConfig.fromJson(it) }
                }
            }
            return ConfigStore(
                version = o.optInt("version", 0),
                updatedAt = o.optLong("updatedAt", 0L),
                cards = cards,
                types = types,
            )
        }
    }
}

// ---------------------------------------------------------------- JSON convenience

internal fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    val out = ArrayList<String>(length())
    for (i in 0 until length()) {
        val v = optString(i, "")
        if (v.isNotEmpty()) out.add(v)
    }
    return out
}
