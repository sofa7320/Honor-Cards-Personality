package com.cardpersonality.honor.hook

import android.view.View
import android.widget.ImageView
import com.cardpersonality.honor.data.CardKey
import com.cardpersonality.honor.data.CardTypes
import java.lang.reflect.Method

/** A card as detected inside the wallet process. */
data class CardIdentity(
    val type: String,
    val id: String,
    val name: String,
    val subtitle: String = "",
    /** Wallet's current remote face image URL (null for bundled-drawable faces, e.g. access). */
    val faceUrl: String? = null,
) {
    val fileKey: String get() = CardKey(type, id).fileKey
}

/**
 * Reflection-based card identity extraction (obfuscation-tolerant, APK 11.0.3.305).
 *
 * Identity chains follow work/re/cards/FINDINGS.md §13 (first non-empty getter wins,
 * numeric ids <= 0 are treated as absent for template/preview beans).
 */
object CardIdentityExtractor {
    /** Class-simpleName hint -> logical type. Order matters (first contains() match wins). */
    private val TYPE_HINTS: List<Pair<String, String>> = listOf(
        "TransitCard" to CardTypes.TRANSIT,
        "TrafficCard" to CardTypes.TRANSIT,
        "CampusCard" to CardTypes.CAMPUS,
        "AccessCard" to CardTypes.ACCESS,
        "BankCard" to CardTypes.BANK,
        "EBikeKey" to CardTypes.EBIKE,
        "BleDk" to CardTypes.CARKEY,
        "CarKey" to CardTypes.CARKEY,
        "RideCode" to CardTypes.EBIKE,
        "Medicare" to CardTypes.BANK,
    )

    private val ID_CHAINS: Map<String, List<String>> = mapOf(
        CardTypes.TRANSIT to listOf("getAid", "getRecordId", "getOrderId"),
        CardTypes.BANK to listOf("getCardId", "getCardAid", "getVirtualCardNum"),
        CardTypes.CAMPUS to listOf("getAid", "getRecordId", "getCardId"),
        CardTypes.CARKEY to listOf("getKeyID", "getPassId", "getUid", "getAid", "getUuid", "getId"),
        CardTypes.ACCESS to listOf("getUid", "getAid", "getAccessCardRecordId", "getRecordId"),
        CardTypes.EBIKE to listOf("getEbikeId", "getEbikeRecordId", "getCardUid", "getAid", "getUid"),
    )

    private val GENERIC_ID_GETTERS = listOf(
        "getAid", "getCardId", "getCardNo", "getRecordId", "getUid", "getPhysicalCardId",
        "getVin", "getKeyId", "getBankCardId", "getCardNumber", "getSerialNumber", "getId",
    )

    private val NAME_GETTERS = listOf(
        "getCustomName", "getName", "getCardName", "getDisplayName", "getTitle", "getNickName",
    )

    /**
     * Extracts type+identity from a wallet card object; null when it is not a known card model.
     *
     * IMPORTANT (ANR fix): hot hook paths must pass `includeFaceUrl = false` (the default) so we
     * never reflectively invoke face-source getters (getFaceImage/getCardPic/...). Those getters
     * may themselves be hooked (see FaceHookEngine.hookFaceSourceGetters), and invoking them from
     * inside hook code can re-enter the after-hook / provider IPC on the wallet main thread.
     * The reported `faceUrl` is filled from the getter after-hook's `param.getResult()` instead.
     */
    fun of(card: Any?, includeFaceUrl: Boolean = false): CardIdentity? {
        card ?: return null
        val type = typeOf(card.javaClass) ?: return null
        val id = identityOf(type, card) ?: return null
        if (id.isEmpty()) return null
        val name = firstString(card, NAME_GETTERS) ?: id
        return CardIdentity(type, id, name, faceUrl = if (includeFaceUrl) faceUrlOf(type, card) else null)
    }

    /** BleDk/EBike faceStyle id (>= 1000 means a remote URL face; below is a bundled drawable). */
    fun faceIdOf(card: Any?): Long? {
        card ?: return null
        return try {
            invokeString(card, "getFaceId")?.toLongOrNull()
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * The wallet's CURRENT remote face image URL for this card (reported to the panel so it can
     * show the current face even before the user customizes anything).
     *
     * Per work/re cards findings:
     *  - transit: TransitCard#getFaceImage
     *  - bank:    BankCard#getCardPic
     *  - campus:  CampusCard#getCardFaceImage
     *  - carkey:  BleDkBean#getImageSelected ("0" / empty = no URL)
     *  - ebike:   EBikeKeyBean#getCardPicUrl only for faceId >= 1000 (bundled below that)
     *  - access:  no URL (bundled drawable / natively custom bitmap)
     * All reflection failures are swallowed (null).
     */
    fun faceUrlOf(type: String, card: Any): String? {
        return try {
            when (type) {
                CardTypes.TRANSIT -> cleanUrl(invokeString(card, "getFaceImage"))
                CardTypes.BANK -> cleanUrl(invokeString(card, "getCardPic"))
                CardTypes.CAMPUS -> cleanUrl(invokeString(card, "getCardFaceImage"))
                CardTypes.CARKEY -> cleanUrl(invokeString(card, "getImageSelected"))?.takeUnless { it == "0" }
                CardTypes.EBIKE -> {
                    val faceId = invokeString(card, "getFaceId")?.toLongOrNull()
                    if (faceId != null && faceId < 1000L) {
                        null // classic bundled face
                    } else {
                        cleanUrl(invokeString(card, "getCardPicUrl"))
                    }
                }
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun cleanUrl(value: String?): String? {
        val v = value?.trim()
        if (v.isNullOrEmpty() || v == "null" || v == "0") return null
        return v
    }

    /** Type-specific identity with the documented fallback chain. */
    fun identityOf(type: String, card: Any): String? {
        val chain = ID_CHAINS[type] ?: GENERIC_ID_GETTERS
        for (getter in chain) {
            val raw = invokeString(card, getter) ?: continue
            if (!isUsableId(raw)) continue
            return raw
        }
        // Bank fallback: bankCode + "|" + span (last four digits).
        if (type == CardTypes.BANK) {
            val bankCode = invokeString(card, "getBankCode")
            val span = invokeString(card, "getSpan")
            if (!bankCode.isNullOrEmpty() && !span.isNullOrEmpty()) return "$bankCode|$span"
        }
        return null
    }

    /** Numeric ids <= 0 are placeholder/template beans; empty strings are absent. */
    private fun isUsableId(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.all { it.isDigit() }) {
            val value = trimmed.toLongOrNull() ?: return true
            return value > 0L
        }
        return true
    }

    /** Detects the logical card type from a model class name (walking superclasses and interfaces). */
    fun typeOf(clazz: Class<*>): String? {
        var c: Class<*>? = clazz
        var depth = 0
        while (c != null && depth < 6) {
            val simple = c.simpleName ?: ""
            for ((hint, type) in TYPE_HINTS) {
                if (simple.contains(hint, ignoreCase = true)) return type
            }
            for (iface in c.interfaces) {
                val name = iface.simpleName ?: ""
                for ((hint, type) in TYPE_HINTS) {
                    if (name.contains(hint, ignoreCase = true)) return type
                }
            }
            c = c.superclass
            depth++
        }
        return null
    }

    /** Compact diagnostics for a card object (used in log lines). */
    fun describeForLog(card: Any?): String {
        card ?: return "null"
        val cls = card.javaClass
        val sb = StringBuilder().append(cls.name)
        val getters = (ID_CHAINS.values.flatten() + GENERIC_ID_GETTERS + NAME_GETTERS).distinct()
        for (getter in getters) {
            val value = invokeString(card, getter) ?: continue
            if (value.isEmpty()) continue
            sb.append(' ').append(getter).append('=').append(value)
        }
        return sb.toString()
    }

    fun firstString(target: Any, getters: List<String>): String? {
        for (getter in getters) {
            val v = invokeString(target, getter)
            if (!v.isNullOrEmpty()) return v
        }
        return null
    }

    private fun invokeString(target: Any, getter: String): String? {
        return try {
            val method: Method = findZeroArg(target.javaClass, getter) ?: return null
            method.isAccessible = true
            val value = method.invoke(target) ?: return null
            when (value) {
                is String -> value
                is Number -> value.toString()
                is CharSequence -> value.toString()
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun findZeroArg(clazz: Class<*>, name: String): Method? {
        var c: Class<*>? = clazz
        var depth = 0
        while (c != null && depth < 8) {
            c.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }?.let { return it }
            c = c.superclass
            depth++
        }
        return null
    }

    /** Resource entry name of a view id, or its numeric id when the name is unavailable. */
    fun viewIdName(view: View): String {
        val id = view.id
        if (id == View.NO_ID) return "no-id"
        return try {
            view.resources.getResourceEntryName(id)
        } catch (_: Throwable) {
            "0x" + Integer.toHexString(id)
        }
    }

    /** True when [view] is an ImageView (subclasses included). */
    fun asImageView(view: View?): ImageView? = view as? ImageView
}
