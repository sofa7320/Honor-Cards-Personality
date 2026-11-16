package com.cardpersonality.honor.hook.oppo

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
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
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
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * OPPO / 欧加钱包 ("com.finshell.wallet") card-face engine — ROUND-13 (0.2.9).
 *
 * Round-13 redesign based on `work/re/oppo` research (L0 pairing + L1 capture + FIX list):
 *  - **L0 pairing** hooks record (view, NfcCard) together, so identity never depends on URL guessing;
 *    every pairing also reports the card to the panel (recordCard) and either replaces the face or
 *    schedules an original-face snapshot.
 *  - **L1 capture** (three channels): (a) wallet Glide reload with a local file path (keeps the
 *    wallet's rounded-corner transform), (b) `FaceCapture.schedule(view, identity)` for the
 *    already-drawn drawable (R2), (c) in-process read of local absolute-path/file:// faces (R3)
 *    followed by saveOriginalFace (http(s) is left to the panel downloader).
 *  - **FIX list**: CNIV param counts (g1=4/h1=2/j1=2, setSvgUrl/r1), typeOfNfcCard() mapping
 *    (incl. 12/7/99/33 merges; unknown types are not reported), no direct setImageBitmap for views
 *    without outline (URL→local-file argument swap instead), config prewarm in install(), URL
 *    normalization + viewToIdentity/urlToIdentity dual tables, engine-state pair/capture/source
 *    counters.
 *
 * Fail-open: main wallet process only; every hook body wrapped; missing class/method logs once; the
 * Honor engine is untouched.
 */
object OppoFaceHookEngine {
    const val WALLET_PACKAGE = "com.finshell.wallet"

    private val handles = CopyOnWriteArrayList<XC_MethodHook.Unhook>()
    @Volatile private var installed = false
    @Volatile private var armed = false
    @Volatile private var configReceiverRegistered = false
    @Volatile private var walletPackage: String = WALLET_PACKAGE

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cardpersonality-oppo-worker").apply { isDaemon = true }
    }

    /** L0 pairing: view -> identity (WeakHashMap so recycled/reused views do not leak). */
    private val viewToIdentity = Collections.synchronizedMap(WeakHashMap<View, CardIdentity>())

    /** URL (normalized + original aliases) -> identity. */
    private val urlToIdentity = ConcurrentHashMap<String, CardIdentity>()

    private val bitmapCache = ConcurrentHashMap<String, Bitmap>()
    private val viewUrls = Collections.synchronizedMap(WeakHashMap<ImageView, String>())
    private val hiddenViews = Collections.synchronizedMap(WeakHashMap<View, String>())

    private val detected = ConcurrentHashMap<String, JSONObject>()
    private val lastReportAt = AtomicLong(0L)
    private val replaceCount = AtomicLong(0L)
    private val observedLogCount = AtomicLong(0L)
    private val pairCount = AtomicLong(0L)
    private val captureCount = AtomicLong(0L)
    private val attemptedHooks = AtomicLong(0L)
    private val installedHooks = AtomicLong(0L)
    private val missedTargets = CopyOnWriteArrayList<String>()

    /** faceSource counters: url/file/bitmap/none. */
    private val faceSource = ConcurrentHashMap<String, AtomicLong>()

    private const val CONFIG_CHANGED_ACTION = "com.cardpersonality.honor.CONFIG_CHANGED"
    private const val MAX_URL_LOGS = 20
    private const val MAX_LOCAL_BYTES = 3 * 1024 * 1024
    private const val MAX_LOCAL_LONG_SIDE = 2560

    private val SAFE_HIDE_IDS: Set<String> = setOf(
        "tv_label", "tv_name", "tv_last_digest", "single_tag", "tv_content",
        "card_sign", "card_status",
        "iv_logo", "tv_title", "encrypt_label", "tv_plate_numb", "tv_card_sign", "keyValidView",
        "nfc_consume_card_sign", "nfc_consume_animation_view",
        "nfc_consume_tv_digest", "nfc_consume_tv_pay_text", "mStationStatus",
        "mEncryptLabel", "mCarKeyValidView", "mCarCoverInfoView",
    )

    private data class CnivSpec(val name: String, val params: Int)

    /**
     * CircleNetworkImageView loaders (all of them now replace the String argument with a local JPEG
     * path; the wallet's own Glide reload keeps rounded corners/scale/cache). Signatures verified
     * against 5.52.0: g1=4, h1=2, j1=2.
     */
    private val CNIV_SPECS = listOf(
        CnivSpec("setImageUrl", 1),
        CnivSpec("setImageBitmap", 1),
        CnivSpec("setImageUrlByTemp", 1),
        CnivSpec("setSvgUrl", 1),
        CnivSpec("f1", 3),
        CnivSpec("e1", 3),
        CnivSpec("i1", 3),
        CnivSpec("r1", 3),
        CnivSpec("g1", 4),
        CnivSpec("h1", 2),
        CnivSpec("j1", 2),
        CnivSpec("A0", 1),
        CnivSpec("y0", 2),
        CnivSpec("x0", 1),
        CnivSpec("F0", 1),
    )

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
        "getCardImg", "getNewCardImg", "getLocalImgPath", "getLocalImgSelPath",
        "getCardImageUrl", "getImgUrl", "getImageUrl", "getCardImgUrl", "getCardFaceUrl",
    )

    private val NFC_CARD_CLASS = "com.nearme.wallet.db.NfcCard"

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
            HLog.i("OPPO engine r13: installing fail-open hooks (wallet=$walletPackage)")
            ProviderClient.logOp("oppo", "init", "pkg=$walletPackage ver=0.2.9")
            hookFaceUrlGetters(classLoader)      // H-C observe
            hookPairing(classLoader)             // L0 pairing (also reports cards + captures)
            hookNetworkImageViews(classLoader)   // H-A replacement
            hookAtomicFaceViews(classLoader)     // H-B auxiliary
            hookBrightnessLoader(classLoader)    // H-E brightness
            hookBrightnessFetch(classLoader)     // H-E2
            ensureRefreshReceiver()
            armed = handles.isNotEmpty()
            HLog.i("OPPO engine r13: armed=$armed hooks=${handles.size}")
            ProviderClient.logOp(
                "oppo",
                "armed",
                "pkg=$walletPackage hooks=${handles.size} attempted=${attemptedHooks.get()} " +
                    "installed=${installedHooks.get()} missed=${missedTargets.size}",
            )
            // FIX-4: warm the config snapshot (first frames otherwise resolve()==null).
            worker.execute {
                try {
                    ProviderClient.config(force = true)
                    ProviderClient.logOp("oppo", "config-prewarmed", "wallet=$walletPackage")
                } catch (_: Throwable) {
                }
            }
        } catch (t: Throwable) {
            HLog.e("OPPO engine install failed (fail-open)", t)
            ProviderClient.logOp("oppo", "init-fail", t.javaClass.simpleName + ": " + t.message)
        }
    }

    fun isArmed(): Boolean = armed

    // ================================================================ H-C: observe getters

    private fun hookFaceUrlGetters(cl: ClassLoader) {
        for (className in FACE_URL_CLASSES) {
            try {
                val cls = Class.forName(className, false, cl)
                for (methodName in FACE_URL_GETTERS) {
                    for (method in cls.declaredMethods) {
                        if (method.name != methodName || method.parameterCount != 0) continue
                        if (method.returnType != String::class.java) continue
                        installObserveHook(cls, method, className, methodName)
                    }
                }
                for (method in cls.methods) {
                    if (method.parameterCount != 0) continue
                    if (method.returnType != String::class.java) continue
                    if (method.name !in FACE_URL_GETTERS) continue
                    installObserveHook(cls, method, className, method.name)
                }
            } catch (t: Throwable) {
                recordMiss("aclass:$className", t)
            }
        }
        ProviderClient.logOp("oppo", "hc-installed", "classes=${FACE_URL_CLASSES.size}")
    }

    private fun installObserveHook(
        cls: Class<*>,
        method: java.lang.reflect.Method,
        className: String,
        methodName: String,
    ) {
        val unhook = attemptHook("observe:$className#$methodName") {
            method.isAccessible = true
            XposedBridge.hookMethod(method, safeAfter("OPPO-obs-$className-$methodName") { param ->
                val url = param.getResult() as? String
                if (url.isNullOrBlank()) return@safeAfter
                observeUrl(url, param.thisObject, className, methodName)
            })
        }
        if (unhook != null) handles.add(unhook)
    }

    private fun observeUrl(url: String, card: Any?, className: String, source: String) {
        val identity = identityOf(card, className) ?: fallbackIdentity(url, className, source)
        aliasUrl(url, identity)
        val isFallback = identity.id.startsWith("url:")
        recordCard(identity, if (isFallback) url else sanitizedFaceUrl(url))
        val n = observedLogCount.incrementAndGet()
        if (n <= MAX_URL_LOGS) {
            ProviderClient.logOp(
                "oppo",
                "url-observed",
                "#$n src=${className.substringAfterLast('.')}::$source id=${identity.id} " +
                    "type=${identity.type} fallback=$isFallback url=${maskUrl(url)}",
            )
        }
    }

    // ================================================================ L0: pairing hooks

    private fun hookPairing(cl: ClassLoader) {
        // L0-1/L0-2: QuickCardNfcCardView#h(NfcCard,boolean) / #setNfcCardData(NfcCard)
        hookPairMethod(
            cl,
            "com.finshell.quickcardpkg.view.QuickCardNfcCardView",
            "h",
            1,
            cardArg = 0,
            viewSource = PairSource.THIS_VIEW_WITH_ID,
        )
        hookPairMethod(
            cl,
            "com.finshell.quickcardpkg.view.QuickCardNfcCardView",
            "setNfcCardData",
            1,
            cardArg = 0,
            viewSource = PairSource.THIS_VIEW_WITH_ID,
        )
        // L0-3: cardframe.e#bindData(NfcCard,int) + reflected field mCardImage
        hookPairMethod(
            cl,
            "com.nearme.pay.business.consume.cardframe.e",
            "bindData",
            2,
            cardArg = 0,
            viewSource = PairSource.REFLECT_FIELD,
            reflectField = "mCardImage",
        )
        // L0-4/5: DragonflyConsumeCardsFrameController#T(CircleNetworkImageView, NfcCard) / #N(View, NfcCard)
        hookPairMethod(
            cl,
            "com.nearme.pay.dragonfly.widget.DragonflyConsumeCardsFrameController",
            "T",
            2,
            cardArg = 1,
            viewSource = PairSource.ARG,
            viewArg = 0,
        )
        hookPairMethod(
            cl,
            "com.nearme.pay.dragonfly.widget.DragonflyConsumeCardsFrameController",
            "N",
            2,
            cardArg = 1,
            viewSource = PairSource.ARG,
            viewArg = 0,
        )
        // L0-6: b0.a#d(Context, NfcCard, String, String) -> no view, but report the card
        hookPairMethod(
            cl,
            "com.nearme.pay.utils.b0.a",
            "d",
            4,
            cardArg = 1,
            viewSource = PairSource.NONE,
        )
        // L0-7: FinUINfcCardFaceView#showCardImg(String,String,boolean,int) + field mThemeImg
        hookPairMethod(
            cl,
            "com.finshell.finui.widget.atomic.card.FinUINfcCardFaceView",
            "showCardImg",
            4,
            cardArg = -1,
            viewSource = PairSource.REFLECT_FIELD,
            reflectField = "mThemeImg",
        )
        ProviderClient.logOp("oppo", "l0-installed", "pair hooks registered")
    }

    private enum class PairSource { ARG, THIS_VIEW_WITH_ID, REFLECT_FIELD, NONE }

    private fun hookPairMethod(
        cl: ClassLoader,
        className: String,
        methodName: String,
        paramCount: Int,
        cardArg: Int,
        viewSource: PairSource,
        viewArg: Int = -1,
        reflectField: String? = null,
    ) {
        try {
            val cls = Class.forName(className, false, cl)
            val methods = cls.declaredMethods.filter {
                it.name == methodName && it.parameterCount == paramCount
            }
            if (methods.isEmpty()) {
                recordMiss("pair:${className.substringAfterLast('.')}#$methodName/$paramCount", null)
                return
            }
            for (method in methods) {
                val unhook = attemptHook("pair:${className.substringAfterLast('.')}#$methodName") {
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, safeAfter("OPPO-pair-$className-$methodName") { param ->
                        val card = if (cardArg >= 0) param.args?.getOrNull(cardArg) else null
                        val view = when (viewSource) {
                            PairSource.ARG -> viewAsImageView(param.args?.getOrNull(viewArg), param.thisObject)
                            PairSource.THIS_VIEW_WITH_ID -> quickCardImageView(param.thisObject)
                            PairSource.REFLECT_FIELD -> reflectViewField(param.thisObject, reflectField)
                            PairSource.NONE -> null
                        }
                        pair(view, card, className, methodName)
                    })
                }
                if (unhook != null) handles.add(unhook)
            }
            ProviderClient.logOp("oppo", "l0-method", "${className.substringAfterLast('.')}#$methodName ok=${methods.size}")
        } catch (t: Throwable) {
            recordMiss("pairclass:$className", t)
        }
    }

    /** Records (view -> identity), reports the card, then replaces or captures the face. */
    private fun pair(view: View?, card: Any?, className: String, source: String) {
        val identity = identityOf(card, className) ?: run {
            val url = firstGetter(card, "getCardImg")
            if (url != null) fallbackIdentity(url, className, source) else null
        } ?: return
        if (view != null) {
            viewToIdentity[view] = identity
        }
        val faceUrl = firstGetter(card, "getCardImg")
        if (!faceUrl.isNullOrBlank()) {
            aliasUrl(faceUrl, identity)
            handleFaceSource(identity, faceUrl)
        }
        pairCount.incrementAndGet()
        recordCard(identity, sanitizedFaceUrl(faceUrl))
        if (view != null) {
            val resolved = ProviderClient.resolve(identity.type, identity.id)
            if (resolved != null) {
                applyResolvedToView(view, resolved, identity, "pair")
            } else if (view is ImageView) {
                scheduleCapture(view, identity, "pair")
            }
        }
        val n = pairCount.get()
        if (n <= MAX_URL_LOGS) {
            ProviderClient.logOp(
                "oppo",
                "pair",
                "#$n src=${className.substringAfterLast('.')}::$source id=${identity.id} " +
                    "type=${identity.type} view=${view?.javaClass?.simpleName ?: "none"}",
            )
        }
    }

    // ================================================================ L1: face handling

    private fun handleFaceSource(identity: CardIdentity, faceUrl: String) {
        val norm = normalizeUrl(faceUrl)
        if (norm.isNullOrBlank()) return
        if (!isHttpUrl(norm)) {
            // B 型：本地绝对路径 / file:// —— hook 进程（钱包 uid）可读，直接回传。
            worker.execute { readLocalFaceAndSend(identity, norm) }
        }
        // A 型 http(s) 交给面板下载：recordCard 里会带上 faceUrl。
    }

    private fun readLocalFaceAndSend(identity: CardIdentity, path: String) {
        try {
            if (!ProviderClient.faceSnapshotNeeded(identity.type, identity.id)) return
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
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_LOCAL_LONG_SIDE) {
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
            val ok = ProviderClient.saveOriginalFace(identity.type, identity.id, bytes)
            if (ok) {
                bumpSource("file")
                ProviderClient.logOp("oppo", "local-file-captured", "${identity.id} ${bytes.size}B src=$path")
            } else {
                bumpSource("none")
            }
        } catch (t: Throwable) {
            ProviderClient.logOp("oppo", "local-file-fail", t.javaClass.simpleName)
            bumpSource("none")
        }
    }

    private fun scheduleCapture(view: ImageView, identity: CardIdentity, source: String) {
        try {
            if (!ProviderClient.faceSnapshotNeeded(identity.type, identity.id)) return
            FaceCapture.schedule(view, identity)
            captureCount.incrementAndGet()
            bumpSource("bitmap")
            if (captureCount.get() <= MAX_URL_LOGS) {
                ProviderClient.logOp("oppo", "capture-scheduled", "${identity.id} src=$source")
            }
        } catch (t: Throwable) {
            ProviderClient.logOp("oppo", "capture-fail", t.javaClass.simpleName)
        }
    }

    /** Replaces a face through the wallet's own Glide (local path) to keep rounded corners. */
    private fun applyResolvedToView(view: View, resolved: ProviderClient.ResolvedFace, identity: CardIdentity, source: String) {
        val local = ensureLocalFaceFile(resolved) ?: return
        if (callWalletLoad(view, local.absolutePath)) {
            bumpSource("file")
            noteReplace(identity, resolved.version, source)
        } else if (view is ImageView) {
            // Fallback only when the view has no wallet loader: bake rounded corners ourselves and
            // set the bitmap on the main thread.
            worker.execute {
                val bitmap = decodeProviderBitmap(resolved) ?: return@execute
                val rounded = roundedCopy(bitmap, view.resources.displayMetrics.density)
                mainHandler.post {
                    try {
                        view.setImageBitmap(rounded)
                        noteReplace(identity, resolved.version, source + "-bitmap")
                        bumpSource("bitmap")
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    /** Invokes the wallet's own `setImageUrl(String)`-style loader on the view (if any). */
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

    /** Bakes round corners (14dp) into a copy for ImageViews without outline clipping. */
    private fun roundedCopy(src: Bitmap, density: Float): Bitmap {
        return try {
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val radius = 14f * density
            val rect = RectF(0f, 0f, src.width.toFloat(), src.height.toFloat())
            canvas.clipPath(Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) })
            canvas.drawBitmap(src, 0f, 0f, paint)
            out
        } catch (_: Throwable) {
            src
        }
    }

    // ================================================================ H-A: CNIV loaders

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
                                val view = param.thisObject as? View
                                val identity = lookupIdentity(view, url) ?: return
                                val resolved = ProviderClient.resolve(identity.type, identity.id)
                                if (resolved != null) {
                                    val local = ensureLocalFaceFile(resolved) ?: return
                                    // FIX-3: keep rounded corners/cache by letting the wallet's own
                                    // Glide load our local JPEG instead of setImageBitmap.
                                    param.args[0] = local.absolutePath
                                    bumpSource("file")
                                    noteReplace(identity, resolved.version, "CNIV#${spec.name}")
                                } else if (view is ImageView) {
                                    scheduleCapture(view, identity, "CNIV#${spec.name}")
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

    private fun lookupIdentity(view: View?, url: String): CardIdentity? {
        if (view != null) {
            viewToIdentity[view]?.let { return it }
        }
        val norm = normalizeUrl(url)
        if (norm != null) urlToIdentity[norm]?.let { return it }
        return urlToIdentity[url]
    }

    // ================================================================ H-B: Fin* views

    private val ATOMIC_FACE_VIEWS = listOf(
        Triple("com.finshell.finui.widget.atomic.FinBigCardFaceView", "setCardFaceUrl", 1),
        Triple("com.finshell.finui.widget.atomic.card.FinCustomCardView", "setCardFaceUrl", 1),
        Triple("com.finshell.finui.widget.atomic.FinSmallCardFaceView", "setImageBitmap", 1),
        Triple("com.finshell.finui.widget.atomic.card.FinUINfcCardFaceView", "showCardImg", 2),
    )

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
                                    val identity = lookupIdentity(null, url) ?: return
                                    val resolved = ProviderClient.resolve(identity.type, identity.id)
                                    if (resolved != null) {
                                        val local = ensureLocalFaceFile(resolved) ?: return
                                        param.args[0] = local.absolutePath
                                        bumpSource("file")
                                        noteReplace(identity, resolved.version, "$className#$methodName")
                                    } else {
                                        val view = reflectViewField(param.thisObject, "mThemeImg")
                                            ?: innerFaceView(param.thisObject)
                                        if (view is ImageView) scheduleCapture(view, identity, "H-B")
                                    }
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

    private fun innerFaceView(host: Any?): ImageView? {
        if (host is ImageView) return host
        val view = host as? View ?: return null
        for (name in listOf("card_img", "iv_card_img", "smallCardFaceView")) {
            try {
                val id = view.resources.getIdentifier(name, "id", view.context.packageName)
                if (id != 0) (view.findViewById<View>(id) as? ImageView)?.let { return it }
            } catch (_: Throwable) {
            }
        }
        return findFirstImageView(view)
    }

    // ================================================================ H-E: brightness

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
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
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
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam?) {
                                try {
                                    val url = param?.args?.getOrNull(stringIndex) as? String ?: return
                                    val identity = lookupIdentity(null, url) ?: return
                                    val resolved = ProviderClient.resolve(identity.type, identity.id) ?: return
                                    val local = ensureLocalFaceFile(resolved) ?: return
                                    param.args[stringIndex] = local.absolutePath
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

    // ================================================================ helpers

    private fun customBitmap(url: String): Bitmap? {
        val identity = lookupIdentity(null, url) ?: return null
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
        } catch (_: Throwable) {
            null
        }
    }

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

    private fun noteReplace(identity: CardIdentity, version: Int, label: String) {
        val n = replaceCount.incrementAndGet()
        HLog.limWithSummary("oppo_replace_${identity.fileKey}", 8) {
            "OPPO face replace $label: ${identity.fileKey} v=$version (n=$n)"
        }
        if (n <= MAX_URL_LOGS) {
            ProviderClient.logOp("oppo", "face-applied", "${identity.id} v=$version via=$label")
        }
    }

    // ================================================================ identity / types

    private fun identityOf(card: Any?, className: String): CardIdentity? {
        card ?: return null
        return try {
            val type = typeOfNfcCard(card, className) ?: return null
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
                name = firstGetter(card, "getDisplayName")
                    ?: firstGetter(card, "getCardName")
                    ?: firstGetter(card, "getName")
                    ?: id,
            )
        } catch (_: Throwable) {
            null
        }
    }

    /** OPPO NfcCard cardType -> our 6 supported types; unknown -> null (do not report). */
    private fun typeOfNfcCard(card: Any?, className: String): String? {
        val raw = firstGetter(card, "getCardType")
            ?: firstGetter(card, "getSubCardType")
            ?: firstGetter(card, "getTrafficTag")
        when (raw) {
            "5" -> return "transit"
            "6", "10" -> return "access"
            "9", "15" -> return "carkey"
            "1", "2", "8" -> return "bank"
            "12" -> return "bank"      // 数字人民币（模块无对应 type，就近归并）
            "14" -> return "campus"
            "21" -> return "ebike"
            "7" -> return "bank"       // eID（模块无对应 type）
            "99" -> return "transit"   // 超级卡
            "33" -> return "transit"   // 第三方码
            null -> Unit
            else -> return null
        }
        // NfcCardDetail has no getCardType; fall back to the model class.
        return typeFallbackForClass(className)
    }

    private fun typeFallbackForClass(className: String): String? = when {
        className.contains("PayCardInfo") ||
            className.contains("CardDetailRspVo") ||
            className.contains("CardDetailRsp") -> "bank"
        className.contains("CarInfo") ||
            className.contains("DigitalKey") ||
            className.contains("VerifyIssuer") -> "carkey"
        className.contains("NfcCard") -> "transit"
        className.contains("EntranceCard") -> "access"
        className.contains("UidInfoDTO") -> "transit"
        className.contains("CardTheme") -> "transit"
        else -> null
    }

    private fun fallbackIdentity(url: String, className: String, source: String): CardIdentity {
        val hash = sha256Hex(url).take(24)
        return CardIdentity(
            type = if (className.contains("NfcCardDetail")) "transit" else "transit",
            id = "url:$hash",
            name = "未识别卡（$source）",
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

    private fun reflectViewField(target: Any?, fieldName: String?): ImageView? {
        if (target == null || fieldName == null) return null
        return try {
            var cls: Class<*>? = target.javaClass
            var depth = 0
            while (cls != null && depth < 6) {
                val field = cls.declaredFields.firstOrNull { it.name == fieldName }
                if (field != null) {
                    field.isAccessible = true
                    return field.get(target) as? ImageView
                }
                cls = cls.superclass
                depth++
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun quickCardImageView(host: Any?): ImageView? {
        val view = host as? View ?: return null
        try {
            val id = view.resources.getIdentifier("nfc_consume_card_img", "id", view.context.packageName)
            if (id != 0) {
                (view.findViewById<View>(id) as? ImageView)?.let { return it }
            }
        } catch (_: Throwable) {
        }
        return findFirstImageView(view)
    }

    private fun viewAsImageView(candidate: Any?, host: Any?): ImageView? {
        if (candidate is ImageView) return candidate
        val container = candidate as? View ?: host as? View ?: return null
        return innerFaceView(container) ?: findFirstImageView(container)
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

    // ================================================================ URL normalization

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

    /** Only http(s) URLs are reported (panel downloads those); local/file faces go through R3. */
    private fun sanitizedFaceUrl(url: String?): String? {
        if (url == null) return null
        val norm = normalizeUrl(url) ?: return null
        return if (isHttpUrl(norm)) norm else null
    }

    private fun aliasUrl(url: String, identity: CardIdentity) {
        urlToIdentity[url] = identity
        normalizeUrl(url)?.let { urlToIdentity[it] = identity }
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

    // ================================================================ hides / refresh

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
        val entries: List<Pair<ImageView, CardIdentity>> = try {
            synchronized(viewToIdentity) {
                viewToIdentity.entries.mapNotNull { e ->
                    val v = e.key
                    val i = e.value
                    if (v is ImageView && i != null && v.isAttachedToWindow) v to i else null
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
        for ((view, identity) in entries) {
            try {
                val resolved = ProviderClient.resolve(identity.type, identity.id)
                if (resolved != null) applyResolvedToView(view, resolved, identity, "refresh")
            } catch (_: Throwable) {
            }
        }
        ProviderClient.logOp("oppo", "refresh", "views=${entries.size}")
    }

    // ================================================================ bookkeeping / diagnostics

    private fun attemptHook(label: String, block: () -> XC_MethodHook.Unhook?): XC_MethodHook.Unhook? {
        attemptedHooks.incrementAndGet()
        val unhook = try {
            hookLogged(label) { block() }
        } catch (_: Throwable) {
            null
        }
        if (unhook != null) installedHooks.incrementAndGet() else recordMiss(label, null)
        return unhook
    }

    private fun recordMiss(target: String, t: Throwable?) {
        if (missedTargets.size < 80) missedTargets.add(target)
        val detail = if (t != null) "$target (${t.javaClass.simpleName})" else target
        HLog.lim("oppo_miss", 50) { "OPPO target missing: $detail" }
        ProviderClient.logOp("oppo", "hook-miss", detail)
    }

    private fun bumpSource(source: String) {
        faceSource.getOrPut(source) { AtomicLong(0L) }.incrementAndGet()
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
                .put("faceSource", sources)
                .put("missed", JSONArray(missedTargets.toList()))
                .toString()
        } catch (_: Throwable) {
            "{}"
        }
    }

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
                .put("wallet", walletPackage)
                .put("walletKind", Wallets.kindOf(walletPackage))
                .put("walletLabel", Wallets.labelOf(walletPackage))
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
