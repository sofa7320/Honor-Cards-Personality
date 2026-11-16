package com.cardpersonality.honor.hook.oppo
import com.cardpersonality.honor.hook.api.HookBridge
import com.cardpersonality.honor.hook.api.MethodHook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.cardpersonality.honor.hook.CardIdentity
import com.cardpersonality.honor.hook.FaceCapture
import com.cardpersonality.honor.hook.HLog
import com.cardpersonality.honor.hook.ProviderClient
import com.cardpersonality.honor.hook.Wallets
import com.cardpersonality.honor.hook.hookLogged
import com.cardpersonality.honor.hook.safeAfter
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * OPPO / 欧加钱包 ("com.finshell.wallet") card-face engine — ROUND-14 (0.2.10).
 *
 * Ports the friend module's (com.crrashh.opcardcustomizer) OPPO capability onto our legacy
 * XposedBridge + provider architecture:
 *  - widget hooks: 14 CircleNetworkImageView methods + 4 Fin* container setters; every String
 *    argument is replaced with a **file:// path inside the wallet private filesDir**
 *    (`files/oppo_faces/card_<md5>.jpg`) so the wallet's own Glide loads it (rounded corners kept,
 *    no content:// compatibility risk);
 *  - model getters (11 classes, after, read-only): record url -> (aid/appCode/cardType/cardNo,
 *    name, balance) and report cards with name/type/balance;
 *  - wallet-side replacement map `files/cardpersonality_oppo_map.json` so replacement keeps working
 *    while the panel provider is unavailable; invalidated on config version change;
 *  - `isCardFace`: target class is one of the 4 containers or an ancestor within 8 parents is;
 *  - diagnostics: engine_state widgetHits/fileReplacements/recordedModels/bridgeMode + OpLog;
 *  - CONFIG_CHANGED: clear caches and reload registered (WeakReference) views.
 *
 * Fail-open, main wallet process only, Honor engine untouched; no OPPO device for validation
 * (static only).
 */
object OppoFaceHookEngine {
    const val WALLET_PACKAGE = "com.finshell.wallet"

    private val handles = CopyOnWriteArrayList<MethodHook.Unhook>()
    @Volatile private var installed = false
    @Volatile private var armed = false
    @Volatile private var configReceiverRegistered = false
    @Volatile private var walletPackage: String = WALLET_PACKAGE

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cardpersonality-oppo-worker").apply { isDaemon = true }
    }

    private val viewToIdentity = Collections.synchronizedMap(WeakHashMap<View, CardIdentity>())
    private val viewToLastUrl = Collections.synchronizedMap(WeakHashMap<View, String>())
    private val urlToIdentity = ConcurrentHashMap<String, CardIdentity>()

    /** friend module metadata per face URL: name/type/balance (read-only observation). */
    private val urlMeta = ConcurrentHashMap<String, FaceMeta>()

    private val bitmapCache = ConcurrentHashMap<String, Bitmap>()
    private val hiddenViews = Collections.synchronizedMap(WeakHashMap<View, String>())

    private val detected = ConcurrentHashMap<String, JSONObject>()
    private val lastReportAt = AtomicLong(0L)
    private val observedLogCount = AtomicLong(0L)
    private val pairCount = AtomicLong(0L)
    private val captureCount = AtomicLong(0L)
    private val widgetHits = AtomicLong(0L)
    private val fileReplacements = AtomicLong(0L)
    private val recordedModels = AtomicLong(0L)
    private val replaceCount = AtomicLong(0L)
    private val attemptedHooks = AtomicLong(0L)
    private val installedHooks = AtomicLong(0L)
    private val missedTargets = CopyOnWriteArrayList<String>()
    private val faceSource = ConcurrentHashMap<String, AtomicLong>()
    private val hitLogged = ConcurrentHashMap<String, AtomicBoolean>()

    // Round-15 diagnostics: heartbeat + counters so the panel can tell "not injected" from
    // "injected but no cards observed".
    @Volatile private var processName: String = ""
    @Volatile private var isMainProcess: Boolean = false
    @Volatile private var classLoaderName: String = ""
    private val observedModels = AtomicLong(0L)
    private val reportedCards = AtomicLong(0L)
    private val sentCardKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val installedLabels = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val skippedNoIdentity = AtomicLong(0L)
    private val skipPlaceholder = AtomicLong(0L)
    private val lastFaceSource = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var lastHookReportAt: Long = 0L
    @Volatile private var heartbeatSent = false
    @Volatile private var daoHooksInstalled = false
    @Volatile private var heartbeatContext: android.content.Context? = null
    @Volatile private var applicationHookInstalled = false
    private val retriesScheduled = AtomicBoolean(false)

    private const val CONFIG_CHANGED_ACTION = "com.cardpersonality.honor.CONFIG_CHANGED"

    /** Key wallet classes whose presence proves the engine is injected into the right dex. */
    private val CLASS_PROBES = linkedMapOf(
        "CircleNetworkImageView" to "com.finshell.finui.widget.imageview.CircleNetworkImageView",
        "NfcCard" to "com.nearme.wallet.db.NfcCard",
        "NfcCardRoomDao_Impl" to "com.nearme.wallet.db.NfcCardRoomDao_Impl",
        "FinBigCardFaceView" to "com.finshell.finui.widget.atomic.FinBigCardFaceView",
        "CardCoverSettingUtil" to "com.finshell.finui.utils.CardCoverSettingUtil",
    )

    private const val MODULE_VERSION = "0.2.10"
    private const val MAX_URL_LOGS = 20
    private const val MAX_LOCAL_BYTES = 3 * 1024 * 1024
    private const val MAX_LOCAL_LONG_SIDE = 2560
    private const val MAX_FACE_BYTES = 700 * 1024
    private const val MAX_FACE_LONG_SIDE = 1600
    private const val OPPO_FACES_DIR = "oppo_faces"
    private const val OPPO_MAP_FILE = "cardpersonality_oppo_map.json"

    private val CARD_FACE_CONTAINERS = setOf(
        "com.finshell.finui.widget.atomic.FinBigCardFaceView",
        "com.finshell.finui.widget.atomic.card.FinCustomCardView",
        "com.finshell.finui.widget.atomic.FinSmallCardFaceView",
        "com.finshell.finui.widget.atomic.card.FinUINfcCardFaceView",
    )

    private val CNIV_NAMES = setOf(
        "setImageUrl", "setImageBitmap", "e1", "g1", "j1", "k1", "d1", "a1", "b1",
        "f1", "h1", "i1", "setImageUrlByTemp", "A0",
        // extras from earlier rounds (low-level funnels)
        "r1", "setSvgUrl", "y0", "x0", "F0",
    )

    private val CONTAINER_METHODS = mapOf(
        "com.finshell.finui.widget.atomic.FinBigCardFaceView" to setOf("setCardFaceUrl"),
        "com.finshell.finui.widget.atomic.card.FinCustomCardView" to setOf("setCardFaceUrl"),
        "com.finshell.finui.widget.atomic.FinSmallCardFaceView" to setOf("setImageBitmap", "setCardFaceUrl"),
        "com.finshell.finui.widget.atomic.card.FinUINfcCardFaceView" to setOf("showCardImg"),
    )

    /** Friend module CARD_MODEL_GETTERS (11 classes, after observe only, values never modified). */
    private val CARD_MODEL_GETTERS = linkedMapOf(
        "com.nearme.wallet.db.NfcCard" to setOf("getCardImg", "getNewCardImg", "getLocalImgPath"),
        "com.nearme.wallet.db.NfcCardDetail" to setOf("getCardImg", "getCardImgUrl", "getLocalImgPath"),
        "com.nearme.nfc.domain.transit.rsp.CardDetailRsp" to setOf("getCardImg", "getNewCardImg"),
        "com.nearme.pay.domain.rsp.CardDetailRspVo" to setOf("getCardImg"),
        "com.nearme.pay.domain.rsp.PayCardInfo" to setOf("getCardImg"),
        "com.finshell.wallet.supercard.net.rsp.CardInfo" to setOf("getCardImg"),
        "com.nearme.wallet.db.CarInfo" to setOf("getCardImg"),
        "com.nearme.nfc.domain.door.rsp.CardTheme" to setOf("getCardImg"),
        "com.nearme.wallet.entrance.bean.EntranceCard" to setOf("getCardImg"),
        "com.nearme.iccoa.net.VerifyIssuerCardRsp" to setOf("getCardImg"),
        "com.nearme.wallet.nfc.bus.net.BackupCard" to setOf("getCardImg"),
    )

    private val NAME_GETTERS = listOf(
        "getCardName", "getDisplayName", "getName", "getNickName", "getCardNickName",
    )

    private val BALANCE_GETTERS = listOf("getAcctAmount", "getBalance")

    /** DAO elements are plain Room entities; try every known face-url getter. */
    private val FACE_URL_GETTERS_FOR_DAO = listOf(
        "getCardImg", "getNewCardImg", "getLocalImgPath", "getLocalImgSelPath",
        "getCardImgUrl", "getImgUrl", "getImageUrl", "getCardFaceUrl",
    )

    private data class FaceMeta(val name: String, val type: String?, val balance: String)

    private data class MapEntry(val file: String, val fileKey: String, val version: Int)

    /** url -> wallet-side replacement map (memory cache of the JSON on disk). */
    private val urlToFile = ConcurrentHashMap<String, MapEntry>()
    @Volatile private var mapLoaded = false

    // ================================================================ install

    fun install(classLoader: ClassLoader, processName: String?, walletPackage: String = WALLET_PACKAGE) {
        if (installed) return
        installed = true
        this.walletPackage = walletPackage
        this.processName = processName ?: ""
        this.classLoaderName = classLoader.toString()
        // Round-16: relaxed default-process detection (OPPO may use pkg or pkg:main).
        this.isMainProcess = this.processName.isEmpty() ||
            this.processName == walletPackage ||
            this.processName == "$walletPackage:main"
        try {
            ProviderClient.walletPackage = walletPackage
            // Round-18: OPPO IPC is the loopback socket bridge (ContentProvider is unreachable
            // because of targetSdk 36 package visibility; non-main processes have no Context).
            OppoBridge.start(walletPackage) { engineStateJson() }
            OppoBridge.setConfigListener { refreshAll() }
            OppoBridge.setConnectedListener { forceReportNow() }
            OppoBridge.setCardSnapshotSupplier { cardsJson() }
            OppoBridge.setOriginalInvalidatedListener { type, id -> FaceCapture.invalidate(type, id) }
            FaceCapture.backend = object : FaceCapture.Backend {
                override fun snapshotNeeded(type: String, id: String): Boolean =
                    OppoBridge.originalNeeded(type, id)

                override fun save(type: String, id: String, jpeg: ByteArray): Boolean {
                    val ok = OppoBridge.saveOriginal(type, id, jpeg)
                    if (ok) OppoBridge.sendOriginal(type, id, jpeg)
                    return ok
                }
            }
        } catch (_: Throwable) {
        }
        // Round-16: NO process gate — every process installs the hooks (class lookups simply miss
        // where the classes are absent) and every process proves injection with a heartbeat.
        try {
            sendHeartbeat("install")
        } catch (_: Throwable) {
        }
        try {
            installApplicationHook(classLoader)
        } catch (_: Throwable) {
        }
        try {
            hookModelGetters(classLoader)          // model observation (all processes)
            hookNetworkImageViews(classLoader)     // CNIV 14 methods
            hookContainers(classLoader)            // Fin* containers
            hookBrightnessLoader(classLoader)      // H-E brightness
            hookBrightnessFetch(classLoader)       // H-E2 brightness fetch
            hookDaoQueries(classLoader, "install") // DAO enumeration fallback (all processes)
            ensureRefreshReceiver()
            armed = handles.isNotEmpty()
            HLog.i("OPPO engine r16: armed=$armed hooks=${handles.size} proc=$processName default=$isMainProcess")
            OppoBridge.oplog(
                "oppo",
                "armed",
                "proc=$processName default=$isMainProcess hooks=${handles.size} " +
                    "attempted=${attemptedHooks.get()} installed=${installedHooks.get()} " +
                    "missed=${missedTargets.size} classProbes=${probeClasses(classLoader)}",
            )
        } catch (t: Throwable) {
            HLog.e("OPPO engine install failed (fail-open)", t)
            OppoBridge.oplog("oppo", "init-fail", t.javaClass.simpleName + ": " + t.message)
        }
        // Heartbeat retries (also covers the case where the context is unavailable right now).
        startHeartbeatLoop()
        // DAO classes may not be loaded at install time: retry once after 3 s.
        mainHandler.postDelayed({
            try {
                hookDaoQueries(classLoader, "delayed")
            } catch (_: Throwable) {
            }
        }, 3_000L)
        // Round-18: config/faces arrive over the loopback bridge (no provider prewarm needed).
    }
    fun isArmed(): Boolean = armed

    // ================================================================ model getters (read-only)

    private fun hookModelGetters(cl: ClassLoader) {
        for ((className, getterNames) in CARD_MODEL_GETTERS) {
            try {
                val cls = Class.forName(className, false, cl)
                for (getterName in getterNames) {
                    for (method in cls.declaredMethods) {
                        if (method.name != getterName || method.parameterCount != 0) continue
                        if (method.returnType != String::class.java) continue
                        val unhook = attemptHook("model:$className#$getterName") {
                            method.isAccessible = true
                            HookBridge.hookMethod(method, safeAfter("OPPO-model-$className-$getterName") { param ->
                                val url = param.getResult() as? String
                                if (url.isNullOrBlank()) return@safeAfter
                                try {
                                    recordModel(param.thisObject, url, className, getterName)
                                } catch (t: Throwable) {
                                    OppoBridge.oplog("oppo", "model-record-fail", t.javaClass.simpleName)
                                }
                            })
                        }
                        if (unhook != null) handles.add(unhook)
                    }
                }
            } catch (t: Throwable) {
                recordMiss("modelclass:$className", t)
            }
        }
        OppoBridge.oplog("oppo", "model-hooks", "classes=${CARD_MODEL_GETTERS.size}")
    }

    /** After-hook body: identity + metadata + report; never modifies the getter value. */
    private fun recordModel(card: Any?, url: String, className: String, getterName: String) {
        val meta = metaOf(card, className)
        urlMeta[url] = meta
        observedModels.incrementAndGet()
        val direct = identityOf(card, className)
        if (direct == null) skippedNoIdentity.incrementAndGet()
        val identity = direct ?: fallbackIdentity(url, className, getterName)
        aliasUrl(url, identity)
        recordedModels.incrementAndGet()
        val firstHit = hitLogged.getOrPut("model:$className#$getterName") { AtomicBoolean(false) }
            .compareAndSet(false, true)
        if (firstHit) {
            OppoBridge.oplog(
                "oppo",
                "model-hit",
                "first $className#$getterName id=${identity.id} type=${identity.type}",
            )
        }
        recordCard(identity, sanitizedFaceUrl(url), meta.name, meta.balance)
        handleFaceSource(identity, url)
        val n = recordedModels.get()
        if (n <= MAX_URL_LOGS) {
            OppoBridge.oplog(
                "oppo",
                "model-record",
                "#$n src=${className.substringAfterLast('.')}::$getterName " +
                    "id=${identity.id} type=${identity.type} name=${meta.name} balance=${meta.balance}",
            )
        }
    }

    private fun metaOf(card: Any?, className: String): FaceMeta {
        val name = NAME_GETTERS.firstNotNullOfOrNull { g -> firstGetter(card, g) } ?: ""
        val type = cardTypeOf(card, className)
        val balance = balanceOf(card)
        return FaceMeta(name, type, balance)
    }

    /**
     * Friend module cardTypeOf rules mapped to our 6 supported types:
     * cardType "1"=bank, "5"=transit, "6"=access(door), "33"=transit(qr);
     * fallback: appCode door -> access; aid A000000632/D156 or appCode kw_/tft_/_bus -> transit;
     * else other -> transit (NFC cards) so the panel can still list/configure them.
     */
    private fun cardTypeOf(card: Any?, className: String): String? {
        when (firstGetter(card, "getCardType")) {
            "1" -> return "bank"
            "5" -> return "transit"
            "6" -> return "access"
            "33" -> return "transit"
        }
        val appCode = firstGetter(card, "getAppCode")?.lowercase().orEmpty()
        if (appCode.startsWith("oppodoor") || appCode.contains("door")) return "access"
        val aid = firstGetter(card, "getAid")?.uppercase().orEmpty()
        if (aid.startsWith("A000000632") || aid.startsWith("D156")) return "transit"
        if (appCode.startsWith("kw_") || appCode.startsWith("tft_") || appCode.contains("_bus")) {
            return "transit"
        }
        return typeFallbackForClass(className)
    }

    private fun balanceOf(card: Any?): String {
        for (getter in BALANCE_GETTERS) {
            try {
                val method = card?.javaClass?.methods?.firstOrNull {
                    it.name == getter && it.parameterCount == 0
                } ?: continue
                when (val value = method.invoke(card)) {
                    is Number -> {
                        val cents = value.toLong()
                        if (cents > 0) return formatCents(cents)
                    }
                    is String -> {
                        val trimmed = value.trim()
                        if (trimmed.isEmpty()) continue
                        val asDouble = trimmed.toDoubleOrNull() ?: continue
                        if (asDouble > 0) return String.format(java.util.Locale.US, "%.2f", asDouble)
                    }
                }
            } catch (_: Throwable) {
            }
        }
        return ""
    }

    private fun formatCents(cents: Long): String {
        return try {
            String.format(java.util.Locale.US, "%.2f", cents / 100.0)
        } catch (_: Throwable) {
            (cents / 100).toString()
        }
    }

    private fun identityOf(card: Any?, className: String): CardIdentity? {
        card ?: return null
        return try {
            val type = cardTypeOf(card, className) ?: return null
            val aid = firstGetter(card, "getAid")
            val appCode = firstGetter(card, "getAppCode")
            val bizId = firstGetter(card, "getBizId")
            val cardNo = firstGetter(card, "getCardNo")
            val cardId = firstGetter(card, "getCardId") ?: firstGetter(card, "getId")
            val id = aid ?: appCode ?: bizId ?: cardNo ?: cardId ?: return null
            if (id.isEmpty()) return null
            CardIdentity(
                type = type,
                id = id,
                name = NAME_GETTERS.firstNotNullOfOrNull { g -> firstGetter(card, g) } ?: id,
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun typeFallbackForClass(className: String): String? = when {
        className.contains("PayCardInfo") -> "bank"
        className.contains("CardDetailRspVo") || className.contains("CardDetailRsp") -> "bank"
        className.contains("CarInfo") || className.contains("DigitalKey") ||
            className.contains("VerifyIssuer") -> "carkey"
        className.contains("NfcCardDetail") || className.contains("NfcCard") -> "transit"
        className.contains("EntranceCard") -> "access"
        className.contains("UidInfoDTO") -> "transit"
        className.contains("CardTheme") -> "transit"
        else -> null
    }

    private fun fallbackIdentity(url: String, className: String, source: String): CardIdentity {
        val hash = sha256Hex(url).take(24)
        return CardIdentity(
            type = typeFallbackForClass(className) ?: "transit",
            id = "url:$hash",
            name = metaOf(null, className).name.ifEmpty { "未识别卡（$source）" },
        )
    }

    private fun firstGetter(target: Any?, getterName: String): String? {
        target ?: return null
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

    // ================================================================ widget hooks

    private fun hookNetworkImageViews(cl: ClassLoader) {
        val cls = try {
            Class.forName("com.finshell.finui.widget.imageview.CircleNetworkImageView", false, cl)
        } catch (t: Throwable) {
            recordMiss("class:CircleNetworkImageView", t)
            return
        }
        var installedMethods = 0
        for (method in cls.declaredMethods) {
            if (method.name !in CNIV_NAMES) continue
            if (method.parameterCount < 1) continue
            if (method.parameterTypes[0] != String::class.java) continue
            installedMethods++
            val unhook = attemptHook("cniv:${method.name}/${method.parameterCount}") {
                method.isAccessible = true
                HookBridge.hookMethod(method, object : MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam?) {
                        try {
                            param ?: return
                            handleWidgetCall(param)
                        } catch (_: Throwable) {
                        }
                    }
                })
            }
            if (unhook != null) handles.add(unhook)
        }
        if (installedMethods == 0) recordMiss("methods:CNIV", null)
        OppoBridge.oplog("oppo", "cniv-hooks", "methods=$installedMethods")
    }

    private fun hookContainers(cl: ClassLoader) {
        for ((className, methodNames) in CONTAINER_METHODS) {
            try {
                val cls = Class.forName(className, false, cl)
                var count = 0
                for (method in cls.declaredMethods) {
                    if (method.name !in methodNames) continue
                    if (method.parameterCount < 1) continue
                    if (method.parameterTypes[0] != String::class.java) continue
                    count++
                    val unhook = attemptHook("container:${className.substringAfterLast('.')}#${method.name}") {
                        method.isAccessible = true
                        HookBridge.hookMethod(method, object : MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam?) {
                                try {
                                    param ?: return
                                    handleWidgetCall(param)
                                } catch (_: Throwable) {
                                }
                            }
                        })
                    }
                    if (unhook != null) handles.add(unhook)
                }
                if (count == 0) recordMiss("methods:$className", null)
            } catch (t: Throwable) {
                recordMiss("container:$className", t)
            }
        }
    }

    /**
     * Friend module `interceptCall` equivalent: for every String argument that resolves to one of
     * our configured OPPO cards, replace it with the wallet-side `file://` face; when the card has
     * no custom face, schedule an original-face snapshot (R2) for real card-face views.
     */
    private fun handleWidgetCall(param: MethodHook.MethodHookParam) {
        val args = param.args ?: return
        if (args.isEmpty()) return
        val host = param.thisObject
        val hostView = host as? View
        val isFace = isCardFace(host, hostView)
        var replacedAny = false
        for (i in args.indices) {
            val url = args[i] as? String ?: continue
            if (url.isEmpty()) continue
            if (url.startsWith("file://")) continue
            val identity = lookupIdentity(hostView, url) ?: continue
            val replacement = resolveReplacement(identity, url)
            if (replacement != null) {
                args[i] = replacement
                replacedAny = true
                bumpSource("file")
                fileReplacements.incrementAndGet()
                noteReplace(identity, "widget#${(host?.javaClass?.simpleName ?: "?")}")
                if (isFace) widgetHits.incrementAndGet()
                recordCard(identity, sanitizedFaceUrl(url), urlMeta[url]?.name ?: "", urlMeta[url]?.balance ?: "")
            } else if (isFace) {
                if (OppoBridge.resolveFace(identity) == null && hostView is ImageView) {
                    scheduleCapture(hostView, identity, "widget")
                }
            }
        }
        if (replacedAny) {
            if (hostView != null) {
                val identity = lookupIdentity(hostView, "")
                if (identity != null) viewToIdentity[hostView] = identity
            }
        }
    }

    /** Friend module isCardFace: target class is a container, or an ancestor within 8 parents is. */
    private fun isCardFace(target: Any?, view: View?): Boolean {
        if (target != null && target.javaClass.name in CARD_FACE_CONTAINERS) return true
        var current: View? = view
        var depth = 0
        while (current != null && depth < 8) {
            if (current.javaClass.name in CARD_FACE_CONTAINERS) return true
            current = current.parent as? View
            depth++
        }
        return false
    }

    private fun innerFaceView(host: Any?): ImageView? {
        if (host is ImageView) return host
        val view = host as? View ?: return null
        for (name in listOf("card_img", "iv_card_img", "smallCardFaceView", "nfc_consume_card_img")) {
            try {
                val id = view.resources.getIdentifier(name, "id", view.context.packageName)
                if (id != 0) (view.findViewById<View>(id) as? ImageView)?.let { return it }
            } catch (_: Throwable) {
            }
        }
        return findFirstImageView(view)
    }

    private fun findFirstImageView(root: View): ImageView? {
        if (root is ImageView) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findFirstImageView(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    // ================================================================ face source (R2/R3)

    private fun handleFaceSource(identity: CardIdentity, faceUrl: String) {
        val norm = normalizeUrl(faceUrl) ?: return
        // Round-22: wallet-native face changed (or reset) → drop the stale original snapshot.
        val previous = lastFaceSource.put(identity.fileKey, norm)
        if (previous != null && previous != norm) {
            OppoBridge.invalidateOriginal(identity.type, identity.id)
            FaceCapture.invalidate(identity.type, identity.id)
        }
        if (!isHttpUrl(norm)) {
            worker.execute { readLocalFaceAndSend(identity, norm) }
        }
    }

    private fun readLocalFaceAndSend(identity: CardIdentity, path: String) {
        try {
            if (!OppoBridge.originalNeeded(identity.type, identity.id)) return
            val file = File(path)
            if (!file.isFile) {
                bumpSource("none")
                return
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                bumpSource("none")
                return
            }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_LOCAL_LONG_SIDE) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bitmap = BitmapFactory.decodeFile(path, opts) ?: run {
                bumpSource("none")
                return
            }
            var bytes = encodeJpeg(bitmap, 95)
            if (bytes == null || bytes.size > MAX_LOCAL_BYTES) {
                bitmap.recycle()
                bitmap = BitmapFactory.decodeFile(path) ?: return
                bytes = encodeJpeg(bitmap, 85)
            }
            bitmap.recycle()
            if (bytes == null || bytes.isEmpty()) {
                bumpSource("none")
                return
            }
            if (OppoBridge.saveOriginal(identity.type, identity.id, bytes)) {
                bumpSource("file")
                OppoBridge.sendOriginal(identity.type, identity.id, bytes)
                OppoBridge.oplog("oppo", "local-file-captured", "${identity.id} ${bytes.size}B")
            } else {
                bumpSource("none")
            }
        } catch (t: Throwable) {
            OppoBridge.oplog("oppo", "local-file-fail", t.javaClass.simpleName)
            bumpSource("none")
        }
    }

    private fun scheduleCapture(view: ImageView, identity: CardIdentity, source: String) {
        try {
            if (!OppoBridge.originalNeeded(identity.type, identity.id)) return
            FaceCapture.schedule(view, identity)
            captureCount.incrementAndGet()
            bumpSource("bitmap")
            if (captureCount.get() <= MAX_URL_LOGS) {
                OppoBridge.oplog("oppo", "capture-scheduled", "${identity.id} src=$source")
            }
        } catch (t: Throwable) {
            OppoBridge.oplog("oppo", "capture-fail", t.javaClass.simpleName)
        }
    }

    // ================================================================ wallet-side file replacement

    /**
     * Resolves a face URL to a `file://` path in the wallet's private filesDir:
     *  1. wallet map cache (survives provider outage) when the config version matches;
     *  2. otherwise fetch our rendered JPEG from the provider, re-encode (long side <=1600,
     *     q88 down to <=700KB) and write `files/oppo_faces/card_<md5>.jpg`, updating the map.
     * Returns null on any failure (caller keeps the original URL).
     */
    /**
     * Round-18: pure local replacement — the panel pushes rendered faces over the loopback bridge
     * and the wallet stores them in `files/cardpersonality/faces/`; we only swap the URL argument
     * with that local `file://` path (no provider, no Context, no network).
     */
    private fun resolveReplacement(identity: CardIdentity, url: String): String? {
        return try {
            val face = OppoBridge.resolveFace(identity) ?: return null
            bumpSource("file")
            OppoBridge.oplog("oppo", "file-replace", "url=${maskUrl(url)} id=${identity.id} file=${face.file.name}")
            "file://" + face.file.absolutePath
        } catch (t: Throwable) {
            OppoBridge.oplog("oppo", "file-replace-fail", t.javaClass.simpleName)
            null
        }
    }
    /** Friend module constraint: source long side <=1600, JPEG q88 stepping down to <=700KB. */
    private fun shrink(bytes: ByteArray): ByteArray? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_FACE_LONG_SIDE * 2) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
            val longSide = maxOf(bitmap.width, bitmap.height)
            if (longSide > MAX_FACE_LONG_SIDE) {
                val scale = MAX_FACE_LONG_SIDE.toFloat() / longSide
                val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
                val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
                if (scaled !== bitmap) bitmap.recycle()
                bitmap = scaled
            }
            var quality = 88
            var out: ByteArray? = null
            while (quality >= 40) {
                val candidate = encodeJpeg(bitmap, quality) ?: break
                if (candidate.size <= MAX_FACE_BYTES || quality == 40) {
                    out = candidate
                    break
                }
                quality -= 8
            }
            bitmap.recycle()
            out
        } catch (_: Throwable) {
            null
        }
    }

    private fun ensureMapLoaded() {
        if (mapLoaded) return
        synchronized(urlToFile) {
            if (mapLoaded) return
            mapLoaded = true
            try {
                val ctx = ProviderClient.appContext() ?: return
                val file = File(ctx.filesDir, OPPO_MAP_FILE)
                if (!file.isFile) return
                val json = JSONObject(file.readText(Charsets.UTF_8))
                val keys = json.keys()
                while (keys.hasNext()) {
                    val url = keys.next()
                    val o = json.optJSONObject(url) ?: continue
                    val f = o.optString("file", "")
                    if (f.isEmpty()) continue
                    urlToFile[url] = MapEntry(
                        file = f,
                        fileKey = o.optString("fileKey", ""),
                        version = o.optInt("version", 0),
                    )
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun persistMap() {
        try {
            val ctx = ProviderClient.appContext() ?: return
            val json = JSONObject()
            for ((url, entry) in urlToFile) {
                json.put(
                    url,
                    JSONObject()
                        .put("file", entry.file)
                        .put("fileKey", entry.fileKey)
                        .put("version", entry.version),
                )
            }
            val file = File(ctx.filesDir, OPPO_MAP_FILE)
            val tmp = File(file.absolutePath + ".tmp")
            tmp.writeText(json.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (_: Throwable) {
        }
    }

    // ================================================================ H-E brightness

    private fun hookBrightnessLoader(cl: ClassLoader) {
        for (className in listOf(
            "com.finshell.finui.utils.CardCoverSettingUtil\$Companion",
            "com.finshell.finui.utils.CardCoverSettingUtil",
        )) {
            try {
                val cls = Class.forName(className, false, cl)
                val methods = cls.declaredMethods.filter {
                    it.name == "loadBitmapSync" && it.parameterCount == 2 &&
                        it.parameterTypes[1] == String::class.java
                }
                for (method in methods) {
                    val unhook = attemptHook("he:$className#loadBitmapSync") {
                        method.isAccessible = true
                        HookBridge.hookMethod(method, object : MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam?) {
                                try {
                                    val url = param?.args?.getOrNull(1) as? String ?: return
                                    val bitmap = customBitmap(url) ?: return
                                    param.result = bitmap
                                } catch (_: Throwable) {
                                }
                            }
                        })
                    }
                    if (unhook != null) handles.add(unhook)
                }
            } catch (_: Throwable) {
            }
        }
    }

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
                        HookBridge.hookMethod(method, object : MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam?) {
                                try {
                                    val url = param?.args?.getOrNull(stringIndex) as? String ?: return
                                    val identity = lookupIdentity(null, url) ?: return
                                    val replacement = resolveReplacement(identity, url) ?: return
                                    param.args[stringIndex] = replacement
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
    }

    private fun customBitmap(url: String): Bitmap? {
        val identity = lookupIdentity(null, url) ?: return null
        val face = OppoBridge.resolveFace(identity) ?: return null
        val key = face.fileKey + "@" + face.version
        bitmapCache[key]?.let { if (!it.isRecycled) return it }
        return try {
            val bytes = face.file.readBytes()
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            bitmapCache[key] = bitmap
            bitmap
        } catch (_: Throwable) {
            null
        }
    }

    // ================================================================ identity lookup / refresh

    private fun lookupIdentity(view: View?, url: String): CardIdentity? {
        if (view == null) {
            val norm = normalizeUrl(url)
            return (if (norm != null) urlToIdentity[norm] else null) ?: urlToIdentity[url]
        }
        viewToIdentity[view]?.let { return it }
        val norm = normalizeUrl(url)
        val identity = (if (norm != null) urlToIdentity[norm] else null)
            ?: urlToIdentity[url]
            ?: return null
        viewToIdentity[view] = identity
        return identity
    }

    private fun ensureRefreshReceiver() {
        if (configReceiverRegistered) return
        val ctx = ProviderClient.appContext() ?: return
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
    }

    /** CONFIG_CHANGED: invalidate memory caches, then reload every registered view. */
    private fun refreshAll() {
        bitmapCache.clear()
        val entries: List<Pair<View, String>> = try {
            synchronized(viewToLastUrl) {
                viewToLastUrl.entries.mapNotNull { e ->
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
                val identity = lookupIdentity(view, url) ?: continue
                val replacement = resolveReplacement(identity, url) ?: continue
                viewToLastUrl[view] = url
                callWalletLoad(view, replacement)
            } catch (_: Throwable) {
            }
        }
        OppoBridge.oplog("oppo", "refresh", "views=${entries.size}")
    }

    private fun callWalletLoad(view: View, path: String): Boolean {
        return try {
            val method = view.javaClass.methods.firstOrNull {
                (it.name == "setImageUrl" || it.name == "setImageBitmap") &&
                    it.parameterCount == 1 && it.parameterTypes[0] == String::class.java
            } ?: return false
            method.invoke(view, path)
            true
        } catch (_: Throwable) {
            false
        }
    }

    // ================================================================ bookkeeping / diagnostics

    private fun attemptHook(label: String, block: () -> MethodHook.Unhook?): MethodHook.Unhook? {
        attemptedHooks.incrementAndGet()
        val unhook = try {
            hookLogged(label) { block() }
        } catch (_: Throwable) {
            null
        }
        if (unhook != null) {
            installedHooks.incrementAndGet()
            installedLabels.add(label)
        } else {
            recordMiss(label, null)
        }
        return unhook
    }

    private fun recordMiss(target: String, t: Throwable?) {
        if (missedTargets.size < 80) missedTargets.add(target)
        val detail = if (t != null) "$target (${t.javaClass.simpleName})" else target
        HLog.lim("oppo_miss", 50) { "OPPO target missing: $detail" }
        OppoBridge.oplog("oppo", "hook-miss", detail)
    }

    private fun bumpSource(source: String) {
        faceSource.getOrPut(source) { AtomicLong(0L) }.incrementAndGet()
    }

    private fun noteReplace(identity: CardIdentity, label: String) {
        val n = replaceCount.incrementAndGet()
        HLog.limWithSummary("oppo_replace_${identity.fileKey}", 8) {
            "OPPO face replace $label: ${identity.fileKey} (n=$n)"
        }
        if (n <= MAX_URL_LOGS) {
            OppoBridge.oplog("oppo", "face-applied", "${identity.id} via=$label")
        }
    }

    /** Round-22: filter the wallet's "添加卡/添加交通卡/..." placeholder entries. */
    private fun isPlaceholderCard(identity: CardIdentity, faceUrl: String?, name: String): Boolean {
        val id = identity.id.trim()
        if (id.isEmpty() || id == "-1" || id == "0") return true
        val label = name.ifBlank { identity.name }.trim()
        if (label.contains("添加")) return true
        if (faceUrl.isNullOrBlank() && label.length <= 2) return true
        return false
    }

    fun engineStateJson(): String {
        return try {
            val sources = JSONObject()
            for ((k, v) in faceSource) sources.put(k, v.get())
            JSONObject()
                .put("walletPackage", walletPackage)
                .put("installed", installed)
                .put("armed", armed)
                .put("installedCount", installedHooks.get())
                .put("attemptedCount", attemptedHooks.get())
                .put("observedUrls", urlToIdentity.size)
                .put("cardCount", detected.size)
                .put("replaceCount", replaceCount.get())
                .put("pairCount", pairCount.get())
                .put("captureCount", captureCount.get())
                .put("widgetHits", widgetHits.get())
                .put("fileReplacements", fileReplacements.get())
                .put("recordedModels", recordedModels.get())
                .put("bridgeMode", "provider")
                .put("observedModels", observedModels.get())
                .put("reportedCards", reportedCards.get())
                .put("sentCards", sentCardKeys.size)
                .put("probesSource", "hooks")
                .put("skippedNoIdentity", skippedNoIdentity.get())
                .put("skipPlaceholder", skipPlaceholder.get())
                .put("lastHookReportAt", lastHookReportAt)
                .put("processName", processName)
                .put("isMainProcess", isMainProcess)
                .put("classLoaderName", classLoaderName)
                .put("heartbeatSent", heartbeatSent)
                .put("daoHooksInstalled", daoHooksInstalled)
                .put("missedCount", missedTargets.size)
                .put("classProbes", probeClasses(null))
                .put("engineVersion", "r16")
                .put("moduleVersion", MODULE_VERSION)
                .put("faceSource", sources)
                .put("missed", JSONArray(missedTargets.toList()))
                .toString()
        } catch (_: Throwable) {
            "{}"
        }
    }

    private fun recordCard(identity: CardIdentity, faceUrl: String?, name: String = "", balance: String = "") {
        if (isPlaceholderCard(identity, faceUrl, name)) {
            skipPlaceholder.incrementAndGet()
            return
        }
        if (pairCount.get() == 0L) pairCount.incrementAndGet()
        val key = Wallets.configKey(walletPackage, identity.fileKey)
        try {
            val o = JSONObject()
                .put("type", identity.type)
                .put("identity", identity.id)
                .put("name", name.ifBlank { identity.name })
                .put("subtitle", "")
                .put("lastSeen", System.currentTimeMillis())
                .put("appElementIds", JSONArray())
                .put("wallet", walletPackage)
                .put("walletKind", Wallets.kindOf(walletPackage))
                .put("walletLabel", Wallets.labelOf(walletPackage))
            if (balance.isNotBlank()) o.put("balance", balance)
            if (faceUrl != null) o.put("faceUrl", faceUrl) else o.put("faceUrl", JSONObject.NULL)
            detected[key] = o
            // Round-18: engine state is sent over the loopback bridge (HELLO), not the provider.
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
            sendAllDetected()
        } catch (_: Throwable) {
        }
    }

    /** Sends the complete detected snapshot and tracks which keys were actually emitted. */
    private fun sendAllDetected() {
        val arr = JSONArray()
        for (o in detected.values) arr.put(o)
        OppoBridge.sendCards(arr.toString())
        sentCardKeys.addAll(detected.keys)
        reportedCards.set(sentCardKeys.size.toLong())
        lastHookReportAt = System.currentTimeMillis()
        sendHeartbeat("hook-report")
    }

    /** Round-19: bridge (re)connected / panel asked — reset the throttle and push everything now. */
    fun forceReportNow() {
        try {
            lastReportAt.set(0L)
            sendAllDetected()
        } catch (_: Throwable) {
        }
    }

    /** Round-19: full detected-card snapshot for the bridge REQ_CARDS pull protocol. */
    fun cardsJson(): String {
        return try {
            val arr = JSONArray()
            for (o in detected.values) arr.put(o)
            arr.toString()
        } catch (_: Throwable) {
            "[]"
        }
    }

    // ================================================================ heartbeat / DAO fallback

    /**
     * Round-18 reliable heartbeat over the loopback bridge: no Context, no provider; the panel
     * records it per process (HELLO) so injection state and class probes are visible in the UI.
     */
    private fun installApplicationHook(cl: ClassLoader) {
        // Round-18: no Application#onCreate hook needed — the bridge has no Context dependency.
    }

    private fun sendHeartbeat(reason: String) {
        try {
            OppoBridge.sendHello(reason)
        } catch (_: Throwable) {
        }
    }

    /**
     * Round-19: classProbes are derived from the hooks that were actually installed, not from
     * Class.forName at heartbeat time (wallet class loaders/reference paths made raw probes false
     * even though the hooks were live). `probesSource:"hooks"` tells the panel the semantics.
     */
    private fun probeClasses(cl: ClassLoader?): String {
        val probes = JSONObject()
        probes.put("CircleNetworkImageView", installedLabels.any { it.startsWith("cniv:") })
        probes.put("NfcCard", installedLabels.any { it.startsWith("model:") })
        probes.put("NfcCardRoomDao_Impl", daoHooksInstalled || installedLabels.any { it.startsWith("dao:") })
        probes.put("FinBigCardFaceView", installedLabels.any { it.startsWith("container:") })
        probes.put("CardCoverSettingUtil", installedLabels.any { it.startsWith("he:") || it.startsWith("he2:") })
        probes.put("probesSource", "hooks")
        return probes.toString()
    }
    /** Round-18: periodic HELLO over the bridge (also proves injection per process). */
    private fun startHeartbeatLoop() {
        try {
            mainHandler.postDelayed(object : Runnable {
                override fun run() {
                    try {
                        sendHeartbeat("periodic")
                    } catch (_: Throwable) {
                    }
                    try {
                        mainHandler.postDelayed(this, 15_000L)
                    } catch (_: Throwable) {
                    }
                }
            }, 15_000L)
        } catch (_: Throwable) {
        }
    }

    /**
     * DAO enumeration fallback: Room `*_Impl` DAO classes return the full card list (0-arg methods
     * returning List) even when no UI ever calls the model getters — hooking them makes card
     * reporting independent of the card-face screens being visited.
     */
    private fun hookDaoQueries(cl: ClassLoader, phase: String) {
        if (daoHooksInstalled && phase == "install") return
        val candidates = listOf(
            "com.nearme.wallet.db.NfcCardRoomDao_Impl",
            "com.nearme.wallet.db.NfcCardDao_Impl",
            "com.nearme.wallet.db.NfcCardDetailDao_Impl",
            "com.nearme.wallet.db.NfcCardDetailRoomDao_Impl",
            "com.nearme.wallet.db.CardDao_Impl",
        )
        var installedAny = false
        for (className in candidates) {
            try {
                val cls = Class.forName(className, false, cl)
                val methods = cls.declaredMethods.filter { m ->
                    m.parameterCount == 0 && java.util.List::class.java.isAssignableFrom(m.returnType)
                }
                for (method in methods) {
                    val label = "dao:${className.substringAfterLast('.')}#${method.name}"
                    if (hitLogged[label]?.get() == true) continue
                    val unhook = attemptHook(label) {
                        method.isAccessible = true
                        HookBridge.hookMethod(
                            method,
                            safeAfter("OPPO-dao-$className-${method.name}") { param ->
                                try {
                                    val list = param.getResult() as? List<*> ?: return@safeAfter
                                    recordDaoCards(list)
                                } catch (_: Throwable) {
                                }
                            },
                        )
                    }
                    if (unhook != null) {
                        handles.add(unhook)
                        installedAny = true
                        hitLogged[label] = AtomicBoolean(false)
                    }
                }
            } catch (t: Throwable) {
                if (phase == "install") recordMiss("dao:$className", t)
            }
        }
        daoHooksInstalled = daoHooksInstalled || installedAny
        if (installedAny) {
            OppoBridge.oplog("oppo", "dao-hooks", "phase=$phase installed=true")
        } else if (phase != "install") {
            OppoBridge.oplog("oppo", "dao-hooks", "phase=$phase installed=false")
        }
    }

    /** Records every card from a DAO List result (no UI dependency). */
    private fun recordDaoCards(list: List<*>) {
        for (item in list) {
            item ?: continue
            try {
                val className = item.javaClass.name
                val url = FACE_URL_GETTERS_FOR_DAO.firstNotNullOfOrNull { g -> firstGetter(item, g) }
                val identity = identityOf(item, className)
                if (identity == null) {
                    skippedNoIdentity.incrementAndGet()
                    if (url == null) continue
                }
                val finalIdentity = identity ?: fallbackIdentity(url ?: "dao:$className", className, "dao")
                if (url != null) aliasUrl(url, finalIdentity)
                val meta = url?.let { urlMeta[it] } ?: metaOf(item, className)
                recordCard(finalIdentity, sanitizedFaceUrl(url), meta.name, meta.balance)
                if (url != null) handleFaceSource(finalIdentity, url) else bumpSource("none")
            } catch (_: Throwable) {
            }
        }
    }

    // ================================================================ URL helpers

    private fun aliasUrl(url: String, identity: CardIdentity) {
        urlToIdentity[url] = identity
        normalizeUrl(url)?.let { urlToIdentity[it] = identity }
    }

    private fun normalizeUrl(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        if (s.startsWith("file://")) s = s.removePrefix("file://")
        val q = s.indexOf('?')
        if (q > 0) s = s.substring(0, q)
        s = try {
            URLDecoder.decode(s, "UTF-8")
        } catch (_: Throwable) {
            s
        }
        return s.trim().ifEmpty { null }
    }

    private fun isHttpUrl(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    private fun sanitizedFaceUrl(url: String?): String? {
        if (url == null) return null
        val norm = normalizeUrl(url) ?: return null
        return if (isHttpUrl(norm)) norm else null
    }

    private fun maskUrl(url: String): String {
        return try {
            val host = try {
                android.net.Uri.parse(url).host ?: ""
            } catch (_: Throwable) {
                ""
            }
            "len=${url.length} host=$host sha=${sha256Hex(url).take(10)}"
        } catch (_: Throwable) {
            "len=${url.length}"
        }
    }

    private fun encodeJpeg(bitmap: Bitmap, quality: Int): ByteArray? {
        return try {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        } catch (_: Throwable) {
            null
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