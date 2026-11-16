package com.cardpersonality.honor.hook.oppo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import com.cardpersonality.honor.hook.CardIdentity
import com.cardpersonality.honor.hook.HLog
import com.cardpersonality.honor.hook.ProviderClient
import com.cardpersonality.honor.hook.Wallets
import com.cardpersonality.honor.hook.hookLogged
import com.cardpersonality.honor.hook.safeAfter
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * OPPO / 欧加钱包 ("com.finshell.wallet") card-face hooks — ROUND-10 v2 (RE findings v2).
 *
 * Fail-open, static-only (no OPPO device). Nothing here can crash or block the wallet: every hook
 * body is wrapped, a missing class/method only logs once, and when anything is uncertain the
 * original wallet call runs unchanged.
 *
 * Hook map (v2):
 *  H-A  PRIMARY — CircleNetworkImageView String loaders (setImageUrl / setImageBitmap(String!) /
 *       setImageUrlByTemp / f1): on a matched OPPO card we SKIP the wallet's loader and self-decode
 *       our provider JPEG (background thread) then call `ImageView.setImageBitmap(Bitmap)`. This
 *       covers card list, detail pages and the consume/swipe page for every card type.
 *  H-B  AUXILIARY — Fin* face views: context registry for refresh + offline compensation
 *       (`FinBigCardFaceView.setCardFaceUrl` has a network gate that early-returns offline).
 *       `FinUINfcCardFaceView.showCardImg` is NEVER skipped (keeps logo/title/brightness logic);
 *       we only schedule a bitmap refresh on its inner `iv_card_img` afterwards.
 *  H-C  OBSERVE ONLY — getCardImg()/getLocalImgPath()/CardTheme URL getters on the card models:
 *       read-only (values are never modified) to build url -> identity (aid/appCode/cardNo/id) and
 *       to observe native "card theme" switches (local image paths).
 *  H-E  CardCoverSettingUtil$Companion#loadBitmapSync: return OUR bitmap for matched cards so the
 *       wallet's white/dark text-color decision is computed from the custom face (readability).
 *  HIDE info-only whitelist (functional views such as buttons/click areas are never hidden);
 *       KEEP is the default (empty list = do nothing).
 */
object OppoFaceHookEngine {
    const val WALLET_PACKAGE = "com.finshell.wallet"

    private val handles = CopyOnWriteArrayList<XC_MethodHook.Unhook>()
    @Volatile private var installed = false
    @Volatile private var armed = false
    @Volatile private var configReceiverRegistered = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val decoder = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cardpersonality-oppo-decode").apply { isDaemon = true }
    }

    /** observed face URL (or local image path) -> card identity. */
    private val urlToIdentity = ConcurrentHashMap<String, CardIdentity>()

    /** last URL seen per ImageView (for config-change refresh). */
    private val viewUrls = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<ImageView, String>(),
    )

    /** decoded custom bitmaps (fileKey@version). */
    private val bitmapCache = ConcurrentHashMap<String, Bitmap>()

    /** views we set GONE (id name) so a hide can be reverted. */
    private val hiddenViews = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<View, String>(),
    )

    private val detected = ConcurrentHashMap<String, JSONObject>()
    private val lastReportAt = AtomicLong(0L)
    private val replaceCount = AtomicLong(0L)

    private const val CONFIG_CHANGED_ACTION = "com.cardpersonality.honor.CONFIG_CHANGED"

    /** Only information/decoration ids may ever be hidden (functional ids are excluded). */
    private val SAFE_HIDE_IDS: Set<String> = setOf(
        // card list item (item_card_package_list.xml) — info only
        "tv_label", "tv_name", "tv_last_digest", "single_tag", "tv_content",
        // big card face (big_card_face_view.xml)
        "card_sign", "card_status",
        // NFC card face (finui_nfc_card_face_view.xml)
        "iv_logo", "tv_title", "encrypt_label", "tv_plate_numb", "tv_card_sign", "keyValidView",
        // consume/swipe page overlays (programmatic)
        "nfc_consume_card_sign", "nfc_consume_animation_view",
        "nfc_consume_tv_digest", "nfc_consume_tv_pay_text", "mStationStatus",
        // door/others
        "mEncryptLabel", "mCarKeyValidView", "mCarCoverInfoView",
        // NEVER: btn_function / iv_action / vClickable / nfc_consume_retry_active_view / any click area
    )

    /** Card models / responses exposing `getCardImg()` (RE v2). */
    private val FACE_URL_CLASSES = listOf(
        "com.nearme.wallet.db.NfcCardDetail",
        "com.nearme.wallet.db.NfcCard",
        "com.nearme.nfc.domain.transit.rsp.CardDetailRsp",
        "com.nearme.pay.domain.rsp.CardDetailRspVo",
        "com.nearme.pay.domain.rsp.PayCardInfo",
        "com.finshell.wallet.supercard.net.rsp.CardInfo",
        "com.nearme.wallet.db.CarInfo",
        "com.nearme.nfc.domain.door.rsp.CardTheme",
        "com.nearme.wallet.entrance.bean.EntranceCard",
        "com.nearme.iccoa.net.VerifyIssuerCardRsp",
        "com.nearme.wallet.nfc.bus.net.BackupCard",
    )

    private val CNIV_CLASS = "com.finshell.finui.widget.imageview.CircleNetworkImageView"
    /**
     * CircleNetworkImageView String loaders (H-A). `e1/i1` (and the low-level g1/h1/j1 variants)
     * are needed for eBike detail (EBikeBigPicViewHolder#V -> mThemeImg.e1(getCardImg(),-1,listener))
     * and BusShifSelectActivity; the trailing listener/flag args do not affect our bitmap path.
     */
    private val CNIV_METHODS = listOf(
        "setImageUrl" to 1,
        "setImageBitmap" to 1, // OPPO: String overload, NOT a Bitmap override
        "setImageUrlByTemp" to 1,
        "f1" to 3,
        "e1" to 3,
        "i1" to 3,
        "g1" to 3,
        "h1" to 3,
        "j1" to 3,
    )

    private val ATOMIC_FACE_VIEWS = listOf(
        Triple("com.finshell.finui.widget.atomic.FinBigCardFaceView", "setCardFaceUrl", 1),
        Triple("com.finshell.finui.widget.atomic.card.FinCustomCardView", "setCardFaceUrl", 1),
        Triple("com.finshell.finui.widget.atomic.FinSmallCardFaceView", "setImageBitmap", 1),
        Triple("com.finshell.finui.widget.atomic.card.FinUINfcCardFaceView", "showCardImg", 2),
    )

    fun install(classLoader: ClassLoader, processName: String?) {
        if (installed) return
        installed = true
        try {
            ProviderClient.walletPackage = WALLET_PACKAGE
        } catch (_: Throwable) {
        }
        try {
            val pkg = WALLET_PACKAGE
            val mainProcess = processName.isNullOrEmpty() || processName == pkg
            if (!mainProcess) {
                HLog.i("OPPO engine: skip process '$processName' (card UI runs in the main process)")
                return
            }
            HLog.i("OPPO engine v2: installing fail-open hooks (wallet=$pkg, static-only validation)")
            hookFaceUrlGetters(classLoader)   // H-C
            hookNetworkImageViews(classLoader) // H-A (primary)
            hookAtomicFaceViews(classLoader)   // H-B (auxiliary)
            hookBrightnessLoader(classLoader)  // H-E
            ensureRefreshReceiver()
            armed = handles.isNotEmpty()
            HLog.i("OPPO engine v2: armed=$armed hooks=${handles.size}")
        } catch (t: Throwable) {
            HLog.e("OPPO engine install failed (fail-open)", t)
        }
    }

    fun isArmed(): Boolean = armed

    // ---------------------------------------------------------------- H-C observe getters

    private fun hookFaceUrlGetters(cl: ClassLoader) {
        for (className in FACE_URL_CLASSES) {
            for (getterName in listOf("getCardImg", "getNewCardImg", "getLocalImgPath")) {
                try {
                    val cls = Class.forName(className, false, cl)
                    for (method in cls.declaredMethods) {
                        if (method.name != getterName || method.parameterCount != 0) continue
                        val unhook = hookLogged("OPPO $className#$getterName (observe)") {
                            method.isAccessible = true
                            XposedBridge.hookMethod(method, safeAfter("OPPO-observe-$className-$getterName") { param ->
                                val url = param.getResult() as? String
                                if (url.isNullOrBlank()) return@safeAfter
                                val identity = identityOf(param.thisObject, className)
                                    ?: return@safeAfter
                                urlToIdentity[url] = identity
                                recordCard(identity, if (getterName == "getCardImg") url else null)
                                HLog.limWithSummary("oppo_url_${identity.fileKey}", 4) {
                                    "OPPO $getterName -> ${identity.fileKey} (${className.substringAfterLast('.')})"
                                }
                            })
                        }
                        if (unhook != null) handles.add(unhook)
                    }
                } catch (t: Throwable) {
                    if (getterName == "getCardImg") {
                        HLog.lim("oppo_missing_${className.substringAfterLast('.')}", 1) {
                            "OPPO class unavailable: $className (${t.javaClass.simpleName})"
                        }
                    }
                }
            }
        }
    }

    private fun identityOf(card: Any?, className: String): CardIdentity? {
        card ?: return null
        return try {
            val aid = firstGetter(card, "getAid")
            val appCode = firstGetter(card, "getAppCode")
            val cardNo = firstGetter(card, "getCardNo")
            val cardId = firstGetter(card, "getCardId") ?: firstGetter(card, "getId")
            // Identity priority: aid (unique) -> appCode -> cardNo -> cardId/id.
            val id = aid ?: appCode ?: cardNo ?: cardId ?: return null
            if (id.isEmpty()) return null
            val type = typeForClass(className)
            val name = firstGetter(card, "getDisplayName")
                ?: firstGetter(card, "getCardName")
                ?: firstGetter(card, "getName")
                ?: id
            CardIdentity(type, id, name)
        } catch (_: Throwable) {
            null
        }
    }

    private fun firstGetter(target: Any, getterName: String): String? {
        return try {
            val method = target.javaClass.methods.firstOrNull {
                it.name == getterName && it.parameterCount == 0
            } ?: return null
            when (val value = method.invoke(target)) {
                null -> null
                is String -> value.trim().ifEmpty { null }
                is Number -> value.toString()
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun typeForClass(className: String): String = when {
        className.contains("PayCardInfo") ||
            className.contains("CardDetailRspVo") ||
            className.contains("CardDetailRsp") -> "bank"
        className.contains("CarInfo") ||
            className.contains("DigitalKey") ||
            className.contains("VerifyIssuer") -> "carkey"
        else -> "transit"
    }

    // ---------------------------------------------------------------- H-A primary: CNIV loaders

    private fun hookNetworkImageViews(cl: ClassLoader) {
        try {
            Class.forName(CNIV_CLASS, false, cl)
        } catch (t: Throwable) {
            HLog.lim("oppo_missing_cniv", 1) { "OPPO CircleNetworkImageView unavailable: ${t.javaClass.simpleName}" }
            return
        }
        for ((methodName, paramCount) in CNIV_METHODS) {
            try {
                val cls = Class.forName(CNIV_CLASS, false, cl)
                val methods = cls.declaredMethods.filter {
                    it.name == methodName && it.parameterCount == paramCount &&
                        it.parameterTypes[0] == String::class.java
                }
                for (method in methods) {
                    val unhook = hookLogged("OPPO $CNIV_CLASS#$methodName (H-A primary)") {
                        method.isAccessible = true
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam?) {
                                try {
                                    param ?: return
                                    val view = param.thisObject as? ImageView ?: return
                                    val url = param.args?.getOrNull(0) as? String ?: return
                                    if (url.isEmpty()) return
                                    if (replaceWithLocalBitmap(view, url, "CircleNetworkImageView#$methodName")) {
                                        // Skip the wallet's original Glide load; we own the pixels.
                                        param.result = null
                                    }
                                } catch (_: Throwable) {
                                }
                            }
                        })
                    }
                    if (unhook != null) handles.add(unhook)
                }
            } catch (t: Throwable) {
                HLog.lim("oppo_cniv_$methodName", 1) {
                    "OPPO $CNIV_CLASS#$methodName unavailable: ${t.javaClass.simpleName}"
                }
            }
        }
    }

    // ---------------------------------------------------------------- H-B auxiliary: Fin* views

    private fun hookAtomicFaceViews(cl: ClassLoader) {
        for ((className, methodName, paramCount) in ATOMIC_FACE_VIEWS) {
            try {
                val cls = Class.forName(className, false, cl)
                val methods = cls.declaredMethods.filter {
                    it.name == methodName && it.parameterCount >= paramCount &&
                        it.parameterTypes[0] == String::class.java
                }
                for (method in methods) {
                    val unhook = hookLogged("OPPO $className#$methodName (H-B auxiliary)") {
                        method.isAccessible = true
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam?) {
                                try {
                                    param ?: return
                                    val url = param.args?.getOrNull(0) as? String ?: return
                                    if (url.isEmpty()) return
                                    val host = param.thisObject ?: return
                                    val identity = urlToIdentity[url] ?: return
                                    // H-B never skips showCardImg (title/logo/brightness must still run).
                                    val isShowCardImg = methodName == "showCardImg"
                                    if (!isShowCardImg) {
                                        // Offline compensation: the wallet may early-return on its
                                        // network gate, so paint our bitmap ourselves too.
                                        val faceView = innerFaceView(host)
                                        if (faceView != null) {
                                            replaceWithLocalBitmap(faceView, url, "$className#$methodName")
                                            viewUrls[faceView] = url
                                        }
                                    } else {
                                        // Schedule our bitmap on the inner face view after the
                                        // wallet's own showCardImg logic set up title/logo.
                                        val faceView = innerFaceView(host)
                                        if (faceView != null) {
                                            mainHandler.post {
                                                try {
                                                    replaceWithLocalBitmap(faceView, url, "$className#showCardImg")
                                                } catch (_: Throwable) {
                                                }
                                            }
                                        }
                                    }
                                    (host as? View)?.let { applyOppoHides(it, identity) }
                                } catch (_: Throwable) {
                                }
                            }
                        })
                    }
                    if (unhook != null) handles.add(unhook)
                }
            } catch (t: Throwable) {
                HLog.lim("oppo_missing_${className.substringAfterLast('.')}", 1) {
                    "OPPO class unavailable: $className (${t.javaClass.simpleName})"
                }
            }
        }
    }

    /** Finds the face ImageView inside a Fin* card container (id card_img / iv_card_img / ...). */
    private fun innerFaceView(host: Any): ImageView? {
        if (host is ImageView) return host
        val view = host as? View ?: return null
        for (name in listOf("card_img", "iv_card_img", "smallCardFaceView")) {
            try {
                val id = view.resources.getIdentifier(name, "id", view.context.packageName)
                if (id != 0) {
                    (view.findViewById<View>(id) as? ImageView)?.let { return it }
                }
            } catch (_: Throwable) {
            }
        }
        // card face view API (FinBigCardFaceView / FinCustomCardView)
        try {
            val getter = host.javaClass.methods.firstOrNull {
                it.name == "getCardFaceView" && it.parameterCount == 0
            }
            if (getter != null) {
                (getter.invoke(host) as? ImageView)?.let { return it }
            }
        } catch (_: Throwable) {
        }
        return null
    }

    // ---------------------------------------------------------------- H-E brightness bitmap

    private fun hookBrightnessLoader(cl: ClassLoader) {
        val candidates = listOf(
            "com.finshell.finui.utils.CardCoverSettingUtil\$Companion",
            "com.finshell.finui.utils.CardCoverSettingUtil",
        )
        for (className in candidates) {
            try {
                val cls = Class.forName(className, false, cl)
                val methods = cls.declaredMethods.filter {
                    it.name == "loadBitmapSync" &&
                        it.parameterCount == 2 &&
                        it.parameterTypes[1] == String::class.java
                }
                for (method in methods) {
                    val unhook = hookLogged("OPPO $className#loadBitmapSync (H-E brightness)") {
                        method.isAccessible = true
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam?) {
                                try {
                                    val url = param?.args?.getOrNull(1) as? String ?: return
                                    if (url.isEmpty()) return
                                    val bitmap = customBitmap(url) ?: return
                                    // Return OUR face so the text-color decision matches it.
                                    param.result = bitmap
                                } catch (_: Throwable) {
                                }
                            }
                        })
                    }
                    if (unhook != null) handles.add(unhook)
                }
                if (methods.isNotEmpty()) return
            } catch (_: Throwable) {
            }
        }
        HLog.lim("oppo_missing_ccsu", 1) { "OPPO CardCoverSettingUtil#loadBitmapSync unavailable (H-E skipped)" }
    }

    // ---------------------------------------------------------------- bitmap provider

    /** Resolves the configured OPPO face for [url] and paints it onto [view]. True when matched. */
    private fun replaceWithLocalBitmap(view: ImageView, url: String, label: String): Boolean {
        val identity = urlToIdentity[url] ?: return false
        val resolved = ProviderClient.resolve(identity.type, identity.id) ?: return false
        viewUrls[view] = url
        val cached = bitmapCache[resolved.fileKey + "@" + resolved.version]
        if (cached != null && !cached.isRecycled) {
            view.setImageBitmap(cached)
            noteReplace(identity, resolved.version, label)
            applyOppoHides(view, identity)
            return true
        }
        decoder.execute {
            try {
                val bitmap = decodeProviderBitmap(resolved) ?: return@execute
                bitmapCache[resolved.fileKey + "@" + resolved.version] = bitmap
                mainHandler.post {
                    try {
                        if (!bitmap.isRecycled) view.setImageBitmap(bitmap)
                        noteReplace(identity, resolved.version, label)
                        applyOppoHides(view, identity)
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
            }
        }
        return true
    }

    /** Synchronous bitmap for H-E (called from the wallet's own background analysis). */
    private fun customBitmap(url: String): Bitmap? {
        val identity = urlToIdentity[url] ?: return null
        val resolved = ProviderClient.resolve(identity.type, identity.id) ?: return null
        val key = resolved.fileKey + "@" + resolved.version
        bitmapCache[key]?.let { if (!it.isRecycled) return it }
        return try {
            val bitmap = decodeProviderBitmap(resolved) ?: return null
            bitmapCache[key] = bitmap
            bitmap
        } catch (_: Throwable) {
            null
        }
    }

    private fun decodeProviderBitmap(face: ProviderClient.ResolvedFace): Bitmap? {
        val ctx = ProviderClient.appContext() ?: return null
        return try {
            ctx.contentResolver.openInputStream(face.contentUri())?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        } catch (t: Throwable) {
            HLog.lim("oppo_decode_fail", 3) {
                "OPPO decode failed ${face.fileKey}: ${t.javaClass.simpleName}"
            }
            null
        }
    }

    private fun noteReplace(identity: CardIdentity, version: Int, label: String) {
        val n = replaceCount.incrementAndGet()
        HLog.limWithSummary("oppo_replace_${identity.fileKey}", 8) {
            "OPPO face replace $label: ${identity.fileKey} v=$version (n=$n)"
        }
    }

    // ---------------------------------------------------------------- info-only hides

    private fun applyOppoHides(root: View, identity: CardIdentity) {
        val hidden = ProviderClient.hiddenElements(identity.type, identity.id)
        if (hidden.isEmpty()) return
        val topRoot = root.rootView
        for (idName in hidden) {
            if (idName !in SAFE_HIDE_IDS) continue
            val target = findByIdName(root, topRoot, idName) ?: continue
            if (target.visibility != View.GONE) {
                target.visibility = View.GONE
                hiddenViews[target] = idName
                HLog.limWithSummary("oppo_hide_$idName", 4) {
                    "OPPO element HIDE $idName for ${identity.fileKey}"
                }
            }
        }
    }

    private fun findByIdName(root: View, topRoot: View?, name: String): View? {
        val id = try {
            root.resources.getIdentifier(name, "id", root.context.packageName)
        } catch (_: Throwable) {
            0
        }
        if (id == 0) return null
        try {
            root.findViewById<View>(id)?.let { return it }
        } catch (_: Throwable) {
        }
        if (topRoot != null && topRoot !== root) {
            try {
                topRoot.findViewById<View>(id)?.let { return it }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    // ---------------------------------------------------------------- refresh

    private fun ensureRefreshReceiver() {
        if (configReceiverRegistered) return
        val ctx = ProviderClient.appContext() ?: return
        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    try {
                        refreshAll()
                    } catch (_: Throwable) {
                    }
                }
            }
            val filter = IntentFilter(CONFIG_CHANGED_ACTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(receiver, filter)
            }
            configReceiverRegistered = true
            HLog.i("OPPO engine: CONFIG_CHANGED refresh receiver registered")
        } catch (_: Throwable) {
        }
    }

    private fun refreshAll() {
        bitmapCache.clear()
        val entries: List<Pair<ImageView, String>> = try {
            synchronized(viewUrls) {
                viewUrls.entries.mapNotNull { e ->
                    val v = e.key
                    val u = e.value
                    if (v == null || u == null || !v.isAttachedToWindow) null else v to u
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
        for ((view, url) in entries) {
            try {
                replaceWithLocalBitmap(view, url, "refresh")
            } catch (_: Throwable) {
            }
        }
        HLog.limWithSummary("oppo_refresh", 3) { "OPPO refresh: ${entries.size} view(s)" }
    }

    // ---------------------------------------------------------------- report

    private fun recordCard(identity: CardIdentity, faceUrl: String?) {
        val key = "oppo|${identity.fileKey}"
        try {
            val o = JSONObject()
                .put("type", identity.type)
                .put("identity", identity.id)
                .put("name", identity.name)
                .put("subtitle", "")
                .put("lastSeen", System.currentTimeMillis())
                .put("appElementIds", JSONArray())
                .put("wallet", WALLET_PACKAGE)
                .put("walletKind", Wallets.kindOf(WALLET_PACKAGE))
                .put("walletLabel", Wallets.labelOf(WALLET_PACKAGE))
            if (faceUrl != null) o.put("faceUrl", faceUrl) else o.put("faceUrl", JSONObject.NULL)
            detected[key] = o
            reportIfDue()
        } catch (_: Throwable) {
        }
    }

    private fun reportIfDue() {
        val now = System.currentTimeMillis()
        val last = lastReportAt.get()
        if (now - last < 10_000L) return
        if (!lastReportAt.compareAndSet(last, now)) return
        if (detected.isEmpty()) return
        try {
            val arr = JSONArray()
            for (o in detected.values) arr.put(o)
            ProviderClient.reportAsync(arr.toString())
        } catch (_: Throwable) {
        }
    }
}
