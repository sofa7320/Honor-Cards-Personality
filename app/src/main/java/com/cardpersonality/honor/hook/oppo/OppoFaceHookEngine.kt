package com.cardpersonality.honor.hook.oppo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * OPPO / 欧加钱包 ("com.finshell.wallet") card-face hooks — ROUND-12 (external analysis 5.52.0).
 *
 * Fail-open, main wallet process only, static-only validation (no OPPO device in this project).
 * Nothing can crash or block the wallet: every body is wrapped, missing classes/methods only log
 * once, and when anything is uncertain the original wallet call runs unchanged.
 *
 * Hooks:
 *  H-A  CircleNetworkImageView loaders. Two strategies:
 *        - view-known setters (setImageUrl/setImageBitmap(String)/setImageUrlByTemp/f1/e1/i1/g1/h1/j1):
 *          matched card -> skip the wallet's Glide load, self-decode our JPEG on a worker and call
 *          ImageView.setImageBitmap(Bitmap).
 *        - URL-only Glide entry points (A0/y0/x0/setSvgUrl/F0, return RequestBuilder/void and have no
 *          view): matched card -> replace the String argument with a LOCAL FILE path of our JPEG in
 *          the wallet cache dir (Glide StringLoader loads local files reliably; content:// is not
 *          verified for OPPO). Never skipped, so callers' return values stay valid.
 *  H-B  aid/aux for Fin* face views (never skips showCardImg; offline compensation).
 *  H-C  observe-only getters (getCardImg/getNewCardImg/getLocalImgPath/...) + field fallback; builds
 *       url -> identity, falls back to a URL-hash identity when the card has no readable id fields so
 *       the panel ALWAYS receives cards.
 *  H-E  CardCoverSettingUtil brightness: loadBitmapSync + fetchBrightnessUnderTextView* return/use
 *       our face so text color matches the custom card.
 *  Diagnostics: every hook install result, missing class/method, first observed URLs (masked, max 20)
 *  and an engine-state JSON (installedCount/observedUrls/cardCount) are journaled via OpLog and
 *  attached to provider "report" for the About/diagnostic page.
 */
object OppoFaceHookEngine {
    const val WALLET_PACKAGE = "com.finshell.wallet"

    private val handles = CopyOnWriteArrayList<XC_MethodHook.Unhook>()
    @Volatile private var installed = false
    @Volatile private var armed = false
    @Volatile private var configReceiverRegistered = false
    @Volatile private var walletPackage: String = WALLET_PACKAGE

    private val mainHandler = Handler(Looper.getMainLooper())
    private val decoder = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cardpersonality-oppo-decode").apply { isDaemon = true }
    }

    /** observed face URL (or local image path) -> card identity. */
    private val urlToIdentity = ConcurrentHashMap<String, CardIdentity>()

    /** last URL seen per ImageView (for config-change refresh). */
    private val viewUrls = Collections.synchronizedMap(WeakHashMap<ImageView, String>())

    /** decoded custom bitmaps (fileKey@version). */
    private val bitmapCache = ConcurrentHashMap<String, Bitmap>()

    /** views we set GONE (id name) so a hide can be reverted. */
    private val hiddenViews = Collections.synchronizedMap(WeakHashMap<View, String>())

    private val detected = ConcurrentHashMap<String, JSONObject>()
    private val lastReportAt = AtomicLong(0L)
    private val replaceCount = AtomicLong(0L)
    private val observedLogCount = AtomicLong(0L)
    private val attemptedHooks = AtomicLong(0L)
    private val installedHooks = AtomicLong(0L)
    private val missedTargets = CopyOnWriteArrayList<String>()

    private const val CONFIG_CHANGED_ACTION = "com.cardpersonality.honor.CONFIG_CHANGED"
    private const val MAX_URL_LOGS = 20

    private val SAFE_HIDE_IDS: Set<String> = setOf(
        "tv_label", "tv_name", "tv_last_digest", "single_tag", "tv_content",
        "card_sign", "card_status",
        "iv_logo", "tv_title", "encrypt_label", "tv_plate_numb", "tv_card_sign", "keyValidView",
        "nfc_consume_card_sign", "nfc_consume_animation_view",
        "nfc_consume_tv_digest", "nfc_consume_tv_pay_text", "mStationStatus",
        "mEncryptLabel", "mCarKeyValidView", "mCarCoverInfoView",
    )

    /** CircleNetworkImageView loaders: name, param count, skipOriginal (view-known setters only). */
    private data class CnivSpec(val name: String, val params: Int, val skipOriginal: Boolean)

    private val CNIV_SPECS = listOf(
        // view-known setters: skip Glide, set our bitmap directly
        CnivSpec("setImageUrl", 1, true),
        CnivSpec("setImageBitmap", 1, true),
        CnivSpec("setImageUrlByTemp", 1, true),
        CnivSpec("f1", 3, true),
        CnivSpec("e1", 3, true),
        CnivSpec("i1", 3, true),
        CnivSpec("g1", 3, true),
        CnivSpec("h1", 3, true),
        CnivSpec("j1", 3, true),
        // URL-only Glide entries: replace the String arg with a local file path (never skip)
        CnivSpec("A0", 1, false),
        CnivSpec("y0", 2, false),
        CnivSpec("x0", 1, false),
        CnivSpec("setSvgUrl", 1, false),
        CnivSpec("F0", 1, false),
    )

    /** Card models/responses whose getters expose the face URL (RE v2 + external analysis). */
    private val FACE_URL_CLASSES = listOf(
        "com.nearme.wallet.db.NfcCardDetail",
        "com.nearme.wallet.db.NfcCard",
        "com.nearme.nfc.domain.transit.rsp.CardDetailRsp",
        "com.nearme.pay.domain.rsp.CardDetailRspVo",
        "com.nearme.pay.domain.rsp.PayCardInfo",
        "com.finshell.wallet.supercard.net.rsp.CardInfo",
        "com.nearme.wallet.db.CarInfo",
        "com.nearme.wallet.db.CardTheme",
        "com.nearme.nfc.domain.door.rsp.CardTheme",
        "com.nearme.wallet.entrance.bean.EntranceCard",
        "com.nearme.wallet.db.UidInfoDTO",
        "com.nearme.pay.domain.rsp.UidInfoDTO",
        "com.finshell.wallet.doorx.net.UidInfoDTO",
        "com.nearme.iccoa.net.VerifyIssuerCardRsp",
        "com.nearme.wallet.nfc.bus.net.BackupCard",
    )

    private val FACE_URL_GETTERS = listOf(
        "getCardImg", "getNewCardImg", "getLocalImgPath",
        "getCardImageUrl", "getImgUrl", "getImageUrl", "getCardImgUrl", "getCardFaceUrl",
    )

    private val FACE_URL_FIELDS = listOf(
        "cardImg", "newCardImg", "localImgPath", "imgUrl", "imageUrl", "cardImgUrl", "cardFaceUrl",
    )

    private val ATOMIC_FACE_VIEWS = listOf(
        Triple("com.finshell.finui.widget.atomic.FinBigCardFaceView", "setCardFaceUrl", 1),
        Triple("com.finshell.finui.widget.atomic.card.FinCustomCardView", "setCardFaceUrl", 1),
        Triple("com.finshell.finui.widget.atomic.FinSmallCardFaceView", "setImageBitmap", 1),
        Triple("com.finshell.finui.widget.atomic.card.FinUINfcCardFaceView", "showCardImg", 2),
    )

    fun install(classLoader: ClassLoader, processName: String?, walletPackage: String = WALLET_PACKAGE) {
        if (installed) return
        installed = true
        this.walletPackage = walletPackage
        try {
            ProviderClient.walletPackage = walletPackage
            ProviderClient.engineStateProvider = { engineStateJson() }
        } catch (_: Throwable) {
        }
        try {
            val mainProcess = processName.isNullOrEmpty() || processName == walletPackage
            if (!mainProcess) {
                HLog.i("OPPO engine: skip process '$processName' (card UI runs in the main process)")
                ProviderClient.logOp("oppo", "skip-process", "pkg=$walletPackage proc=$processName")
                return
            }
            HLog.i("OPPO engine r12: installing fail-open hooks (wallet=$walletPackage)")
            ProviderClient.logOp("oppo", "init", "pkg=$walletPackage ver=0.2.8")
            hookFaceUrlGetters(classLoader)   // H-C
            hookNetworkImageViews(classLoader) // H-A
            hookAtomicFaceViews(classLoader)   // H-B
            hookBrightnessLoader(classLoader)  // H-E
            hookBrightnessFetch(classLoader)   // H-E2
            ensureRefreshReceiver()
            armed = handles.isNotEmpty()
            HLog.i("OPPO engine r12: armed=$armed hooks=${handles.size}")
            ProviderClient.logOp(
                "oppo",
                "armed",
                "pkg=$walletPackage hooks=${handles.size} attempted=${attemptedHooks.get()} " +
                    "installed=${installedHooks.get()} missed=${missedTargets.size}",
            )
        } catch (t: Throwable) {
            HLog.e("OPPO engine install failed (fail-open)", t)
            ProviderClient.logOp("oppo", "init-fail", t.javaClass.simpleName + ": " + t.message)
        }
    }

    fun isArmed(): Boolean = armed

    // ---------------------------------------------------------------- H-C observe getters

    private fun hookFaceUrlGetters(cl: ClassLoader) {
        for (className in FACE_URL_CLASSES) {
            try {
                val cls = Class.forName(className, false, cl)
                // 1) getter methods (multi-candidate names)
                for (methodName in FACE_URL_GETTERS) {
                    for (method in cls.declaredMethods) {
                        if (method.name != methodName || method.parameterCount != 0) continue
                        if (method.returnType != String::class.java) continue
                        installObserveHook(cls, method, className, methodName)
                    }
                }
                // 2) public no-arg getters inherited from superclasses
                for (method in cls.methods) {
                    if (method.parameterCount != 0) continue
                    if (method.returnType != String::class.java) continue
                    if (method.name !in FACE_URL_GETTERS) continue
                    installObserveHook(cls, method, className, method.name)
                }
                // 3) field fallback (some 5.52.0 models expose fields, not getters)
                for (field in cls.declaredFields) {
                    if (field.name !in FACE_URL_FIELDS) continue
                    if (field.type != String::class.java) continue
                    attemptHook("field:$className#${field.name}") {
                        field.isAccessible = true
                        null
                    }
                }
            } catch (t: Throwable) {
                recordMiss("class:$className", t)
            }
        }
        HLog.i("OPPO engine r12: H-C observation classes attempted=${FACE_URL_CLASSES.size}")
        ProviderClient.logOp(
            "oppo",
            "hc-installed",
            "classes=${FACE_URL_CLASSES.size} getters=${FACE_URL_GETTERS.size}",
        )
    }

    private fun installObserveHook(
        cls: Class<*>,
        method: java.lang.reflect.Method,
        className: String,
        methodName: String,
    ) {
        val label = "observe:$className#$methodName"
        val unhook = attemptHook(label) {
            method.isAccessible = true
            XposedBridge.hookMethod(method, safeAfter("OPPO-obs-$className-$methodName") { param ->
                val url = param.getResult() as? String
                if (url.isNullOrBlank()) return@safeAfter
                observeUrl(url, param.thisObject, className, methodName)
            })
        }
        if (unhook != null) handles.add(unhook)
    }

    /** Records a URL -> identity mapping (or a URL-hash fallback) and reports the card. */
    private fun observeUrl(url: String, card: Any?, className: String, source: String) {
        val identity = identityOf(card, className)
            ?: fallbackIdentity(url, className, source)
        urlToIdentity[url] = identity
        val isFallback = identity.id.startsWith("url:")
        recordCard(identity, url, source)
        val n = observedLogCount.incrementAndGet()
        if (n <= MAX_URL_LOGS) {
            val masked = maskUrl(url)
            ProviderClient.logOp(
                "oppo",
                "url-observed",
                "#$n src=${className.substringAfterLast('.')}::$source id=${identity.id} " +
                    "type=${identity.type} fallback=$isFallback url=$masked",
            )
        }
    }

    /** Masked URL for diagnostics: host + length + short hash, no query/path. */
    private fun maskUrl(url: String): String {
        return try {
            val host = try {
                android.net.Uri.parse(url).host ?: ""
            } catch (_: Throwable) {
                ""
            }
            val hash = sha256Hex(url).take(10)
            "len=${url.length} host=$host sha=$hash"
        } catch (_: Throwable) {
            "len=${url.length}"
        }
    }

    private fun fallbackIdentity(url: String, className: String, source: String): CardIdentity {
        val hash = sha256Hex(url).take(24)
        return CardIdentity(
            type = typeForClass(className),
            id = "url:$hash",
            name = "未识别卡（$source）",
        )
    }

    private fun identityOf(card: Any?, className: String): CardIdentity? {
        card ?: return null
        return try {
            val aid = firstGetter(card, "getAid")
            val appCode = firstGetter(card, "getAppCode")
            val cardNo = firstGetter(card, "getCardNo") ?: firstGetter(card, "getCardNum")
            val cardId = firstGetter(card, "getCardId")
                ?: firstGetter(card, "getId")
                ?: firstGetter(card, "getUid")
            val id = aid ?: appCode ?: cardNo ?: cardId ?: return null
            if (id.isEmpty()) return null
            CardIdentity(
                type = typeForClass(className),
                id = id,
                name = firstGetter(card, "getDisplayName")
                    ?: firstGetter(card, "getCardName")
                    ?: firstGetter(card, "getName")
                    ?: id,
            )
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

    // ---------------------------------------------------------------- H-A loaders

    private fun hookNetworkImageViews(cl: ClassLoader) {
        val cls = try {
            Class.forName("com.finshell.finui.widget.imageview.CircleNetworkImageView", false, cl)
        } catch (t: Throwable) {
            recordMiss("class:CircleNetworkImageView", t)
            return
        }
        for (spec in CNIV_SPECS) {
            val matches = cls.declaredMethods.filter {
                it.name == spec.name && it.parameterCount == spec.params &&
                    it.parameterTypes[0] == String::class.java
            }
            if (matches.isEmpty()) {
                recordMiss("method:CNIV#${spec.name}/${spec.params}", null)
                continue
            }
            for (method in matches) {
                val unhook = attemptHook("cniv:${spec.name}/${spec.params}") {
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam?) {
                            try {
                                param ?: return
                                val url = param.args?.getOrNull(0) as? String ?: return
                                if (url.isEmpty()) return
                                val view = param.thisObject as? ImageView
                                if (spec.skipOriginal && view != null) {
                                    if (replaceWithLocalBitmap(view, url, "CNIV#${spec.name}")) {
                                        param.result = null
                                    }
                                } else {
                                    replaceUrlWithLocalFile(param, url)
                                }
                            } catch (_: Throwable) {
                            }
                        }
                    })
                }
                if (unhook != null) handles.add(unhook)
            }
            ProviderClient.logOp("oppo", "ha-method", "${spec.name}/${spec.params} installed=${matches.size}")
        }
    }

    // ---------------------------------------------------------------- H-B auxiliary

    private fun hookAtomicFaceViews(cl: ClassLoader) {
        for ((className, methodName, paramCount) in ATOMIC_FACE_VIEWS) {
            try {
                val cls = Class.forName(className, false, cl)
                val methods = cls.declaredMethods.filter {
                    it.name == methodName && it.parameterCount >= paramCount &&
                        it.parameterTypes[0] == String::class.java
                }
                if (methods.isEmpty()) {
                    recordMiss("method:${className.substringAfterLast('.')}#$methodName", null)
                    continue
                }
                for (method in methods) {
                    val unhook = attemptHook("hb:${className.substringAfterLast('.')}#$methodName") {
                        method.isAccessible = true
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam?) {
                                try {
                                    param ?: return
                                    val url = param.args?.getOrNull(0) as? String ?: return
                                    if (url.isEmpty()) return
                                    val host = param.thisObject ?: return
                                    val identity = urlToIdentity[url] ?: return
                                    val isShowCardImg = methodName == "showCardImg"
                                    if (!isShowCardImg) {
                                        val faceView = innerFaceView(host)
                                        if (faceView != null) {
                                            replaceWithLocalBitmap(faceView, url, "$className#$methodName")
                                            viewUrls[faceView] = url
                                        }
                                    } else {
                                        // never skip showCardImg (logo/title/brightness logic)
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
                ProviderClient.logOp("oppo", "hb-method", "${className.substringAfterLast('.')}#$methodName ok=${methods.size}")
            } catch (t: Throwable) {
                recordMiss("class:$className", t)
            }
        }
    }

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

    // ---------------------------------------------------------------- H-E brightness

    private fun hookBrightnessLoader(cl: ClassLoader) {
        val candidates = listOf(
            "com.finshell.finui.utils.CardCoverSettingUtil\$Companion",
            "com.finshell.finui.utils.CardCoverSettingUtil",
        )
        var installedAny = false
        for (className in candidates) {
            try {
                val cls = Class.forName(className, false, cl)
                val methods = cls.declaredMethods.filter {
                    it.name == "loadBitmapSync" &&
                        it.parameterCount == 2 &&
                        it.parameterTypes[1] == String::class.java
                }
                for (method in methods) {
                    val unhook = attemptHook("he:$className#loadBitmapSync") {
                        method.isAccessible = true
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam?) {
                                try {
                                    val url = param?.args?.getOrNull(1) as? String ?: return
                                    if (url.isEmpty()) return
                                    val bitmap = customBitmap(url) ?: return
                                    param.result = bitmap
                                    installedAny = true
                                } catch (_: Throwable) {
                                }
                            }
                        })
                    }
                    if (unhook != null) {
                        handles.add(unhook)
                        installedAny = true
                    }
                }
            } catch (_: Throwable) {
            }
        }
        if (!installedAny) {
            recordMiss("method:CardCoverSettingUtil#loadBitmapSync", null)
        }
    }

    /** Second brightness entry: fetchBrightnessUnderTextView*(ctx,url,iv,tv,cb) uses its own Glide. */
    private fun hookBrightnessFetch(cl: ClassLoader) {
        for (className in listOf(
            "com.finshell.finui.utils.CardCoverSettingUtil",
            "com.finshell.finui.utils.CardCoverSettingUtil\$Companion",
        )) {
            try {
                val cls = Class.forName(className, false, cl)
                val methods = cls.declaredMethods.filter { it.name.startsWith("fetchBrightnessUnderTextView") }
                for (method in methods) {
                    val stringIndex = method.parameterTypes.indexOf(String::class.java)
                    if (stringIndex < 0) continue
                    val unhook = attemptHook("he2:$className#${method.name}") {
                        method.isAccessible = true
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam?) {
                                try {
                                    val url = param?.args?.getOrNull(stringIndex) as? String ?: return
                                    if (url.isEmpty()) return
                                    replaceUrlWithLocalFile(param, url, index = stringIndex)
                                } catch (_: Throwable) {
                                }
                            }
                        })
                    }
                    if (unhook != null) handles.add(unhook)
                }
                if (methods.isNotEmpty()) {
                    ProviderClient.logOp("oppo", "he2-method", "${methodDesc(className)} fetch*=${methods.size}")
                    return
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun methodDesc(className: String): String = className.substringAfterLast('.')

    // ---------------------------------------------------------------- bitmap / local file

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

    /**
     * H-A URL-only entries: replace the String argument with a local file inside the WALLET cache
     * dir holding our exported JPEG, so the wallet's own Glide loads it. Never skips the call.
     */
    private fun replaceUrlWithLocalFile(
        param: XC_MethodHook.MethodHookParam,
        url: String,
        index: Int = 0,
    ) {
        val identity = urlToIdentity[url] ?: return
        val resolved = ProviderClient.resolve(identity.type, identity.id) ?: return
        val file = ensureLocalFaceFile(resolved) ?: return
        param.args[index] = file.absolutePath
        noteReplace(identity, resolved.version, "local-file")
    }

    /** Copies our provider JPEG into the wallet cache once (readable by the wallet's Glide). */
    private fun ensureLocalFaceFile(resolved: ProviderClient.ResolvedFace): File? {
        val ctx = ProviderClient.appContext() ?: return null
        return try {
            val dir = File(ctx.cacheDir, "cardpersonality_oppo_faces").apply { mkdirs() }
            val file = File(dir, sha256Hex(resolved.fileKey).take(24) + "_v" + resolved.version + ".jpg")
            if (file.isFile && file.length() > 0L) return file
            val tmp = File(file.absolutePath + ".tmp")
            ctx.contentResolver.openInputStream(resolved.contentUri())?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
            if (file.isFile && file.length() > 0L) file else null
        } catch (t: Throwable) {
            ProviderClient.logOp("oppo", "local-file-fail", t.javaClass.simpleName)
            null
        }
    }

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
        // Prefer the local file (same bytes, no provider IPC), fall back to the provider stream.
        try {
            ensureLocalFaceFile(face)?.let { file ->
                BitmapFactory.decodeFile(file.absolutePath)?.let { return it }
            }
        } catch (_: Throwable) {
        }
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
        if (n <= MAX_URL_LOGS) {
            ProviderClient.logOp("oppo", "face-applied", "${identity.id} v=$version via=$label")
        }
    }

    // ---------------------------------------------------------------- hide (info-only)

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
        ProviderClient.logOp("oppo", "refresh", "views=${entries.size}")
    }

    // ---------------------------------------------------------------- hooks bookkeeping

    private fun attemptHook(label: String, block: () -> XC_MethodHook.Unhook?): XC_MethodHook.Unhook? {
        attemptedHooks.incrementAndGet()
        val unhook = try {
            hookLogged(label) { block() }
        } catch (_: Throwable) {
            null
        }
        if (unhook != null) {
            installedHooks.incrementAndGet()
        } else {
            recordMiss(label, null)
        }
        return unhook
    }

    private fun recordMiss(target: String, t: Throwable?) {
        if (missedTargets.size < 60) missedTargets.add(target)
        val detail = if (t != null) "$target (${t.javaClass.simpleName})" else target
        HLog.lim("oppo_miss", 40) { "OPPO target missing: $detail" }
        ProviderClient.logOp("oppo", "hook-miss", detail)
    }

    // ---------------------------------------------------------------- engine state / report

    /** Engine state for diagnostics: counts exposed to the panel + provider report. */
    fun engineStateJson(): String {
        return try {
            JSONObject()
                .put("walletPackage", walletPackage)
                .put("installed", installed)
                .put("armed", armed)
                .put("installedCount", installedHooks.get())
                .put("attemptedCount", attemptedHooks.get())
                .put("observedUrls", urlToIdentity.size)
                .put("cardCount", detected.size)
                .put("replaceCount", replaceCount.get())
                .put("missed", JSONArray(missedTargets.toList()))
                .toString()
        } catch (_: Throwable) {
            "{}"
        }
    }

    private fun recordCard(identity: CardIdentity, faceUrl: String?, source: String) {
        val key = "oppo|${identity.fileKey}"
        try {
            val o = JSONObject()
                .put("type", identity.type)
                .put("identity", identity.id)
                .put("name", identity.name)
                .put("subtitle", "")
                .put("lastSeen", System.currentTimeMillis())
                .put("appElementIds", JSONArray())
                .put("wallet", walletPackage)
                .put("walletKind", Wallets.kindOf(walletPackage))
                .put("walletLabel", Wallets.labelOf(walletPackage))
                .put("identitySource", source)
            if (faceUrl != null) o.put("faceUrl", faceUrl) else o.put("faceUrl", JSONObject.NULL)
            detected[key] = o
            ProviderClient.engineStateProvider = { engineStateJson() }
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

    private fun sha256Hex(text: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            digest.joinToString("") { "%02x".format(it) }
        } catch (_: Throwable) {
            Integer.toHexString(text.hashCode())
        }
    }
}
