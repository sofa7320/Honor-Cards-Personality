package com.cardpersonality.honor.hook
import com.cardpersonality.honor.hook.api.HookBridge
import com.cardpersonality.honor.hook.api.HookHelpers
import com.cardpersonality.honor.hook.api.MethodHook

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.cardpersonality.honor.data.CardInfo
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Wallet-process hook engine — display-layer face replacement.
 *
 * Architecture (work/re/cards/IMPLEMENTATION_CHECKLIST.md, FINDINGS §11-19):
 *  H1  defpackage.nn0#p  — PRE only: record current card (ThreadLocal), source→identity map,
 *      weak ImageView registry, report card. NEVER skips/modifies the original call.
 *  H2  defpackage.nh5#c  — THE replacement point (home swipe / detail / list / notification):
 *      when the source belongs to a configured card, replace args[2] with our content:// Uri,
 *      skip the original and re-invoke the same method with all other args unchanged
 *      (ThreadLocal re-entry guard). Uri object, not String.
 *  H3  defpackage.mv1#a  — card -> face source: return our URI string so preload/cache keys and
 *      list comparisons stay consistent (type-compatible with the original String result).
 *  H5  defpackage.xi#j   — access card (classic bundled faces only reach here); after-hook.
 *  H6  defpackage.pi3#c  — eBike card face; after-hook.
 *  H7-H12 detail adapters (v8a/ff0/ml1/hp1/rc3/jg): after-hook enforcement per bind.
 *  H13 SwipeCardStackView#setAdapter / H14 HwViewPager#setAdapter: capture for refresh.
 *  Refresh: CONFIG_CHANGED broadcast + ContentObserver on content://<authority>/config, then
 *  clear wallet preload caches, rebuild swipe stack / detail pager, reload registered views.
 *  ImageView.setImageDrawable guard + OnPreDraw enforcer: final enforcement.
 *
 * Everything is fail-open: every hook is wrapped; on any exception the wallet keeps working.
 */
object FaceHookEngine {
    const val WALLET_PACKAGE = "com.hihonor.wallet"

    private const val CLASS_NN0 = "nn0"
    private const val CLASS_Z79 = "z79"
    private const val CLASS_LA8 = "la8"
    private const val CLASS_IA8 = "ia8"
    private const val CLASS_NH5 = "nh5"
    private const val CLASS_MV1 = "mv1"
    private const val CLASS_XI = "xi"
    private const val CLASS_PI3 = "pi3"
    private const val CLASS_HV1 = "hv1"
    private const val CLASS_TO6 = "to6"
    private const val CLASS_CACHE_MV1 = "mv1"
    private const val CLASS_STACK = "com.hihonor.wallet.cardmanager.swipecard.SwipeCardStackView"
    private const val CLASS_PAGER = "com.hihonor.uikit.hwviewpager.widget.HwViewPager"
    private const val CLASS_TRANSIT_CARD = "com.hihonor.wallet.serviceinterface.trafficcard.TransitCard"

    private const val ACTION_CONFIG_CHANGED = "com.cardpersonality.honor.CONFIG_CHANGED"
private const val ACTION_RECAPTURE = "com.cardpersonality.honor.RECAPTURE_ORIGINAL"
    private const val CONFIG_URI = "content://${ProviderClient.AUTHORITY}/config"

    /** Detail page adapters: class name -> bind method name (all take (a, int)). */
    private val DETAIL_ADAPTERS: List<Pair<String, String>> = listOf(
        "v8a" to "l",   // transit detail (state label page)
        "ml1" to "l",   // campus detail
        "hp1" to "l",   // car-key detail
        "ff0" to "u",   // bank detail
        "rc3" to "u",   // ebike detail
        "jg" to "u",    // access detail (also used by style preview; H5 excludes that page)
    )

    /** Contexts where the face must stay untouched (style pickers / native replace pages). */
    private val EXCLUDED_CONTEXTS = setOf(
        "AccessCardCustomActivity",
        "EBikeKeyCreatingActivity",
        "EBikeKeyEditCardActivity",
        "CarKeyReplaceCardPageActivity",
    )

    /** App-element view ids we may hide (work/re/cards §5). Functional views are never here. */
    private val SAFE_HIDE_IDS: Set<String> = setOf(
        "tv_card_name", "iv_honor_pay_badge", "tv_bank_code", "iv_overlay",
        "cl_campus_card_container", "gradient_view", "iv_home", "iv_ebike_icon",
        "hw_tip", "tv_card_label", "unactivated",
        "label_layout_nfc", "iv_tag_nfc", "tv_card_label_nfc",
        "label_layout_ble", "iv_tag_ble", "tv_card_label_ble",
    )

    private val handles = CopyOnWriteArrayList<MethodHook.Unhook>()
    @Volatile private var installed = false
    @Volatile private var walletClassLoader: ClassLoader? = null

    private val detected = ConcurrentHashMap<String, CardInfo>()
    /** Round-25: last AccessCard object per card (for model-field recapture). */
    private val accessModels = ConcurrentHashMap<String, WeakReference<Any>>()
    /** Round-32: last bound AccessCard ImageView per card (re-capture even when model is gone). */
    private val accessViews = ConcurrentHashMap<String, WeakReference<ImageView>>()
    /** Round-32: panel asked for a fresh ACCESS original; capture on the next render too. */
    private val accessPendingCapture = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val lastReportAt = AtomicLong(0L)
    private val bindCount = AtomicLong(0L)
    private val nn0Count = AtomicLong(0L)
    private val replacedCount = AtomicLong(0L)

    /** H1: identity of the card currently being bound on this thread (resets per bind). */
    private val currentCard = ThreadLocal<CardIdentity?>()

    /** H2: re-entry guard when we re-invoke nh5#c ourselves. */
    private val nh5Reentrant = ThreadLocal<Boolean>()

    /** Persistent face source string -> resolved custom face (from H1/H3 observations). */
    private val sourceToFace = ConcurrentHashMap<String, ProviderClient.ResolvedFace>()
    /** Face source string -> card identity (populated from the wallet's face-source getters). */
    private val sourceToIdentity = ConcurrentHashMap<String, CardIdentity>()
    /** Views WE set to GONE (id name), so removing a hide can restore them live. */
    private val ownerHiddenViews = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<View, String>(),
    )
    /**
     * Roots whose face could not be applied yet because provider config was not ready (cold-start
     * race, e.g. double-press-power SwipeCardActivity). Retried when config arrives + delays.
     */
    private val pendingFaceRoots = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<View, CardIdentity>(),
    )

    /** Cold-start retry schedule for pending face applications (ms since the bind). */
    private val FACE_RETRY_DELAYS_MS = longArrayOf(300L, 800L, 1500L, 2500L)
    /** Bound adapter/holder roots -> identity (re-apply element hides on CONFIG_CHANGED). */
    private val boundRoots = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<View, CardIdentity>(),
    )

    /** Delayed re-apply passes for element hiding (wallet re-sets visibility after bind). */
    private val HIDE_RETRY_DELAYS_MS = longArrayOf(150L, 400L, 900L)

    private val mainHandler = Handler(Looper.getMainLooper())
    /**
     * ANR fix: strict re-entrancy guard for the face-source getter after-hook. The after-hook must
     * never invoke a hooked getter again (directly or through reflection); if it happens anyway we
     * bail out immediately instead of recursing on the wallet main thread.
     */
    private val inFaceSourceHook = ThreadLocal<Boolean>()
    private val faceSourceReentries = AtomicLong(0L)

    /** H13/H14 captured containers for refresh. */
    @Volatile private var stackRef: WeakReference<Any>? = null
    @Volatile private var stackAdapterRef: WeakReference<Any>? = null
    @Volatile private var pagerRefs = CopyOnWriteArrayList<Pair<WeakReference<Any>, WeakReference<Any>>>()

    fun install(classLoader: ClassLoader) {
        synchronized(this) {
            if (installed) {
                HLog.i("hooks already installed")
                return
            }
            installed = true
            walletClassLoader = classLoader
        }
        HLog.i("installing wallet hooks (display-layer replacement)")

        hookLogged("face source getters (diagnostic + URL->face registry)") { hookFaceSourceGetters(classLoader) }
        hookLogged("$CLASS_NN0#p (H1 record)") { hookFaceChokePoint(classLoader) }
        hookLogged("$CLASS_NH5#c (H2 replace face source)") { hookGlideWrapper(classLoader) }
        hookLogged("$CLASS_MV1#a (H3 card image source)") { hookCardImageSource(classLoader) }
        hookLogged("$CLASS_HV1#a (preload cache safety net)") { hookPreloadCache(classLoader) }
        hookLogged("$CLASS_Z79#d (swipe adapter bind)") { hookAdapterBind(classLoader) }
        hookLogged("$CLASS_Z79#f (swipe adapter data set)") { hookAdapterDataSet(classLoader) }
        hookLogged("$CLASS_LA8#a (home card entry builder)") { hookHomeCardBuilder(classLoader) }
        hookLogged("$CLASS_IA8#setNewData (home card entry list)") { hookHomeCardEntryData(classLoader) }
        hookLogged("$CLASS_TO6#B (卡包 my-cards list)") { hookMyCardsList(classLoader) }
        hookLogged("$CLASS_MV1#c (list thumbnails)") { hookListThumbnails(classLoader) }
        hookLogged("$CLASS_XI#j (H5 access card face)") { hookAccessFace(classLoader) }
        hookLogged("$CLASS_PI3#c (H6 ebike card face)") { hookEbikeFace(classLoader) }
        hookLogged("$CLASS_STACK#setAdapter (H13 capture)") { hookStackAdapter(classLoader) }
        hookLogged("$CLASS_PAGER#setAdapter (H14 capture)") { hookPagerAdapter(classLoader) }
        hookLogged("android.widget.ImageView#setImageDrawable (guard)") { hookImageViewGuard() }
        for ((className, methodName) in DETAIL_ADAPTERS) {
            hookLogged("$className#$methodName (detail adapter)") {
                hookDetailAdapter(classLoader, className, methodName)
            }
        }

        try {
            val ctx = ProviderClient.appContext()
            if (ctx != null) {
                HLog.i("provider probe at install: ctx=${ctx.packageName}")
                ProviderClient.prefetchAsync() // background IPC only (ANR fix v14)
                // Cold-start: when config finally arrives, finish any pending face applications.
                ProviderClient.addConfigListener {
            // Round-23: after a reset / native custom-face change the cached original snapshot must
            // be refreshed so the panel preview shows the wallet's current face.
            try {
                FaceCapture.invalidateAll()
            } catch (_: Throwable) {
            }
                    try {
                        retryPendingFaces()
                    } catch (_: Throwable) {
                    }
                }
                ensureRefreshHooks(ctx)
            } else {
                HLog.i("provider probe deferred until first card bind (no app context yet)")
            }
        } catch (t: Throwable) {
            HLog.e("provider probe at install failed", t)
        }
    }

    // ------------------------------------------------------------------ H1 nn0#p (record only)

    private fun hookFaceChokePoint(cl: ClassLoader): MethodHook.Unhook? {
        val method = findWalletMethod(cl, CLASS_NN0) { m ->
            m.name == "p" && m.parameterTypes.size == 4 &&
                m.parameterTypes[0] == ImageView::class.java &&
                m.parameterTypes[1] == String::class.java
        } ?: run {
            HLog.w("nn0#p not found — dumping possible face-choke methods")
            dumpMethods(cl, CLASS_NN0, "p")
            return null
        }
        HLog.i("nn0#p signature: ${signatureOf(method)}")
        return HookBridge.hookMethod(method, object : MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
                if (param == null) return
                try {
                    onFaceChokePointPre(param)
                } catch (t: Throwable) {
                    HLog.e("nn0#p pre-hook error", t)
                }
            }

            override fun afterHookedMethod(param: MethodHookParam?) {
                if (param == null) return
                try {
                    onFaceChokePointPost(param)
                } catch (t: Throwable) {
                    HLog.e("nn0#p post-hook error", t)
                }
            }
        })
    }

    /** H1 PRE: record only — never modify args, never skip the wallet's own load. */
    private fun onFaceChokePointPre(param: MethodHook.MethodHookParam) {
        val args = param.args ?: return
        val imageView = args.getOrNull(0) as? ImageView
        val source = args.getOrNull(1) as? String
        val identity = CardIdentityExtractor.of(args.getOrNull(2))
        if (identity == null) {
            HLog.limWithSummary("nn0_unknown", 5) {
                "nn0#p unknown card ${CardIdentityExtractor.describeForLog(args.getOrNull(2))}"
            }
            return
        }
        currentCard.set(identity)
        recordCard(identity)
        val resolved = ProviderClient.resolve(identity.type, identity.id)
        if (source != null && resolved != null) {
            sourceToFace[source] = resolved
        }
        if (imageView != null && resolved != null) {
            FaceEnforcer.register(imageView, resolved)
            FaceImageLoader.load(imageView.context, resolved) { bitmap ->
                if (bitmap != null) {
                    FaceEnforcer.applyBitmap(imageView, bitmap)
                    imageView.invalidate()
                    replacedCount.incrementAndGet()
                }
            }
        } else if (imageView != null) {
            // No custom face: snapshot the wallet's real face for the panel (round 2 #1).
            FaceCapture.schedule(imageView, identity)
        }
        HLog.limWithSummary("nn0_pre", 10) {
            "nn0#p RECORD card=${identity.fileKey} source=${source?.take(100)} " +
                "configured=${resolved != null} view=${imageView?.let { CardIdentityExtractor.viewIdName(it) } ?: "none"}"
        }
    }

    private fun onFaceChokePointPost(param: MethodHook.MethodHookParam) {
        val n = nn0Count.incrementAndGet()
        try {
            currentCard.remove()
        } catch (_: Throwable) {
        }
        HLog.limWithSummary("nn0_post", 10) { "nn0#p POST n=$n" }
    }

    // ------------------------------------------------------------------ H2 nh5#c (replacement)

    private fun hookGlideWrapper(cl: ClassLoader): MethodHook.Unhook? {
        val method = findWalletMethod(cl, CLASS_NH5) { m ->
            m.name == "c" && m.parameterTypes.size >= 4 &&
                m.parameterTypes[0] == ImageView::class.java &&
                m.parameterTypes[1] == Context::class.java
        } ?: run {
            HLog.w("nh5#c not found — dumping methods")
            dumpMethods(cl, CLASS_NH5, "c")
            return null
        }
        HLog.i("nh5#c signature: ${signatureOf(method)}")
        return HookBridge.hookMethod(method, object : MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
                if (param == null) return
                if (nh5Reentrant.get() == true) return // our own re-invocation
                try {
                    substituteGlideSource(param)
                } catch (t: Throwable) {
                    HLog.e("nh5#c substitution failed", t)
                }
            }
        })
    }

    /**
     * H2: if the image about to be loaded belongs to a configured card, swap the source for our
     * content:// Uri and re-invoke the exact same method (all other args untouched), so the
     * wallet's placeholder/transform/listener/callback chain stays identical.
     */
    private fun substituteGlideSource(param: MethodHook.MethodHookParam) {
        val args = param.args ?: return
        val imageView = args.getOrNull(0) as? ImageView ?: return
        val ctx = args.getOrNull(1) as? Context ?: imageView.context
        if (isExcludedContext(ctx)) {
            HLog.limWithSummary("nh5_excluded", 3) { "nh5#c skipped in excluded context ${ctx.javaClass.simpleName}" }
            return
        }
        val source = args.getOrNull(2)
        if (source is Uri && source.authority == ProviderClient.AUTHORITY) return // already ours
        val identity = currentCard.get() ?: identityFromView(imageView)
        var face: ProviderClient.ResolvedFace? = identity?.let {
            ProviderClient.resolve(it.type, it.id)
        }
        if (face == null && source is String) {
            face = sourceToFace[source]
        }
        if (face == null) {
            val captureIdentity = identity ?: (source as? String)?.let { sourceToIdentity[it] }
            if (captureIdentity != null) {
                FaceCapture.schedule(imageView, captureIdentity)
            }
            HLog.limWithSummary("nh5_miss", 10) {
                "nh5#c no configured card source=${source?.toString()?.take(120)} " +
                    "identity=${identity?.fileKey ?: "?"} view=${CardIdentityExtractor.viewIdName(imageView)}"
            }
            return
        }
        if (source is String) sourceToFace[source] = face
        FaceEnforcer.register(imageView, face)

        val newArgs = args.copyOf()
        newArgs[2] = face.contentUri()
        // Legacy Xposed: setResult in before-hook skips the original method (void here).
        param.setResult(null)
        val instance = param.thisObject
        val target = param.method as? java.lang.reflect.Method
        if (target == null) {
            HLog.w("nh5#c hook: unexpected non-method member")
            return
        }
        nh5Reentrant.set(true)
        try {
            target.isAccessible = true
            target.invoke(instance, *newArgs)
            replacedCount.incrementAndGet()
            HLog.limWithSummary("nh5_replace", 10) {
                "nh5#c SUBSTITUTED card=${face.fileKey} v=${face.version} " +
                    "view=${CardIdentityExtractor.viewIdName(imageView)} uri=${face.contentUri()}"
            }
        } catch (t: Throwable) {
            HLog.e("nh5#c re-invoke failed (keeping original wallet image)", t)
        } finally {
            nh5Reentrant.set(false)
        }
    }

    // ------------------------------------------------------------------ H3 mv1#a (source helper)

    private fun hookCardImageSource(cl: ClassLoader): MethodHook.Unhook? {
        val method = findWalletMethod(cl, CLASS_MV1) { m ->
            m.name == "a" && m.parameterTypes.size == 1 && !m.parameterTypes[0].isPrimitive &&
                m.returnType == Any::class.java
        } ?: run {
            HLog.w("mv1#a not found — dumping methods")
            dumpMethods(cl, CLASS_MV1, "a")
            return null
        }
        HLog.i("mv1#a signature: ${signatureOf(method)}")
        return HookBridge.hookMethod(method, safeAfter("mv1#a") { param ->
            val identity = CardIdentityExtractor.of(param.args?.getOrNull(0)) ?: return@safeAfter
            val resolved = ProviderClient.resolve(identity.type, identity.id) ?: return@safeAfter
            // re-cards correction: mv1#a must return an android.net.Uri so the hv1 preload key
            // (q0(card)+source) and p's cache lookup both point at our versioned content URI.
            val uri = resolved.contentUri()
            param.setResult(uri)
            HLog.limWithSummary("mv1_replace", 6) { "mv1#a -> $uri (${identity.fileKey})" }
        })
    }

    @Volatile
    private var lastConfigChangeAt = 0L

    /**
     * hv1#a(String key) -> Bitmap preload cache. Safety net per re-cards: during a short window
     * after a config change, force a cache miss for keys carrying our authority so a stale
     * preloaded bitmap cannot win; the normal path then goes nh5 -> H2 -> our URI.
     */
    private fun hookPreloadCache(cl: ClassLoader): MethodHook.Unhook? {
        val method = findWalletMethod(cl, CLASS_HV1) { m ->
            m.name == "a" && m.parameterTypes.size == 1 && m.parameterTypes[0] == String::class.java
        } ?: run {
            HLog.w("hv1#a not found — dumping methods")
            dumpMethods(cl, CLASS_HV1, "a")
            return null
        }
        HLog.i("hv1#a signature: ${signatureOf(method)}")
        return HookBridge.hookMethod(method, object : MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
                try {
                    if (param == null) return
                    val key = param.args?.getOrNull(0) as? String ?: return
                    if (!key.contains(ProviderClient.AUTHORITY)) return
                    val sinceChange = System.currentTimeMillis() - lastConfigChangeAt
                    if (sinceChange in 0..15_000L) {
                        param.setResult(null)
                        HLog.limWithSummary("hv1_miss", 8) { "hv1#a forced miss after config change: $key" }
                    }
                } catch (_: Throwable) {
                }
            }
        })
    }
    // ------------------------------------------------------------------ swipe/home surfaces

    private fun hookAdapterBind(cl: ClassLoader): MethodHook.Unhook? {
        val method = findWalletMethod(cl, CLASS_Z79) { m ->
            m.name == "d" && m.parameterTypes.size == 4 &&
                m.parameterTypes[1] == Int::class.javaPrimitiveType &&
                m.parameterTypes[2] == Boolean::class.javaPrimitiveType &&
                m.parameterTypes[3] == Boolean::class.javaPrimitiveType
        } ?: run {
            HLog.w("z79#d not found — dumping methods")
            dumpMethods(cl, CLASS_Z79, "d")
            return null
        }
        HLog.i("z79#d signature: ${signatureOf(method)}")
        return HookBridge.hookMethod(method, safeAfter("z79#d") { param ->
            val bind = bindCount.incrementAndGet()
            val holder = param.args?.getOrNull(0) ?: return@safeAfter
            val root = viewRootOf(holder)
            val identity = root?.let { identityFromViewRoot(it) }
                ?: cardObjectInHolder(holder)?.let { CardIdentityExtractor.of(it) }
            HLog.limWithSummary("z79_d", 10) {
                "z79#d bind=$bind holder=${holder.javaClass.name} card=${identity?.fileKey ?: "?"}"
            }
            identity?.let { id -> root?.let { r -> afterBind(r, id) } }
        })
    }

    private fun hookAdapterDataSet(cl: ClassLoader): MethodHook.Unhook? {
        val method = findWalletMethod(cl, CLASS_Z79) { m ->
            m.name == "f" && m.parameterTypes.size == 1 &&
                java.util.List::class.java.isAssignableFrom(m.parameterTypes[0])
        } ?: run {
            HLog.w("z79#f not found — dumping methods")
            dumpMethods(cl, CLASS_Z79, "f")
            return null
        }
        return HookBridge.hookMethod(method, safeAfter("z79#f") { param ->
            val list = param.args?.getOrNull(0) as? Collection<*> ?: return@safeAfter
            val identities = ArrayList<CardIdentity>()
            for (item in list) {
                CardIdentityExtractor.of(item)?.let {
                    recordCard(it)
                    identities.add(it)
                }
            }
            HLog.limWithSummary("z79_f", 8) {
                "z79#f dataSet size=${list.size} cards=" + identities.joinToString(";") { it.fileKey }
            }
            reportIfDue()
        })
    }

    private fun hookHomeCardBuilder(cl: ClassLoader): MethodHook.Unhook? {
        val method = findWalletMethod(cl, CLASS_LA8) { m ->
            m.name == "a" && m.parameterTypes.size == 4 &&
                m.parameterTypes[0] == Context::class.java &&
                m.parameterTypes[1] == Any::class.java &&
                View::class.java.isAssignableFrom(m.parameterTypes[2]) &&
                m.parameterTypes[3] == Int::class.javaPrimitiveType
        } ?: run {
            HLog.w("la8#a not found — dumping methods")
            dumpMethods(cl, CLASS_LA8, "a")
            return null
        }
        HLog.i("la8#a signature: ${signatureOf(method)}")
        return HookBridge.hookMethod(method, safeAfter("la8#a") { param ->
            val args = param.args ?: return@safeAfter
            val root = args.getOrNull(2) as? View ?: return@safeAfter
            if (isExcludedContext(root.context)) return@safeAfter
            val identity = CardIdentityExtractor.of(args.getOrNull(1)) ?: return@safeAfter
            recordCard(identity)
            afterBind(root, identity)
            reportIfDue()
        })
    }

    private fun hookHomeCardEntryData(cl: ClassLoader): MethodHook.Unhook? {
        val method = findWalletMethod(cl, CLASS_IA8) { m ->
            m.name.startsWith("setNewData") && m.parameterTypes.size == 1 &&
                java.util.List::class.java.isAssignableFrom(m.parameterTypes[0])
        } ?: run {
            HLog.w("ia8#setNewData not found — dumping methods")
            dumpMethods(cl, CLASS_IA8, "setNewData")
            return null
        }
        return HookBridge.hookMethod(method, safeAfter("ia8#setNewData") { param ->
            val list = param.args?.firstOrNull { it is Collection<*> } as? Collection<*> ?: return@safeAfter
            var count = 0
            for (item in list) {
                val identity = CardIdentityExtractor.of(item) ?: continue
                recordCard(identity)
                count++
            }
            HLog.limWithSummary("ia8_data", 8) { "ia8#setNewData size=${list.size} cards=$count" }
            reportIfDue()
        })
    }

    // ------------------------------------------------------------------ H5/H6 access + eBike

    /**
     * Round-25 door-card spec H1: single APK-wide entry `xi#j(ImageView, AccessCard, ...)`.
     * BEFORE hook:
     *   - reads AccessCard.faceId / cardPicUrl fields (before the original loads anything);
     *   - reports the wallet-native face URL for custom/creative faces (faceId >=1000 / <=-1000);
     *   - with a module face configured: skips the original load and applies our bitmap directly
     *     (no placeholder flash, no async Glide overwrite);
     *   - otherwise captures the wallet's current face deterministically from the model
     *     (classic: f06.a(faceId,0) drawable; custom: download cardPicUrl), not from drawable.
     */
    /**
     * Round-25 door spec H3: the panel deleted the original snapshot / opened the editor; clear the
     * once-per-process capture guard and re-capture deterministically from the cached model.
     */
    private fun handleRecapture(context: Context?, intent: Intent?) {
        try {
            val type = intent?.getStringExtra("type").orEmpty()
            val id = intent?.getStringExtra("id").orEmpty()
            if (type.isBlank() || id.isBlank()) return
            FaceCapture.invalidate(type, id)
            HLog.limWithSummary("recapture_req", 6) { "recapture requested $type:$id" }
            if (type != com.cardpersonality.honor.data.CardTypes.ACCESS) return
            val identity = CardIdentity(type, id, "", "")
            val ctx = context ?: return
            val cl = walletClassLoader ?: return
            // Also capture on the next xi#j render even if the model/view registry is empty.
            accessPendingCapture.add(identity.fileKey)
            val ref = accessModels[identity.fileKey]
            val card = ref?.get()
            if (card != null) {
                val faceIdText = readFieldString(card, "faceId")
                val faceId = faceIdText?.toLongOrNull()
                val url = readFieldString(card, "cardPicUrl")
                captureAccessModel(cl, ctx, identity, faceId, url, "recapture")
            }
            // Renderer may have the view already laid out (no further xi#j call): capture it now.
            val viewRef = accessViews[identity.fileKey]
            val view = viewRef?.get()
            if (view != null) {
                captureAccessView(ctx, view, identity, readFaceId(card))
            }
        } catch (t: Throwable) {
            HLog.lim("recapture_err", 3) { "recapture failed: ${t.javaClass.simpleName}: ${t.message}" }
        }
    }

    private fun readFaceId(card: Any?): String? = try {
        if (card == null) null else readFieldString(card, "faceId")
    } catch (_: Throwable) {
        null
    }

    /** Round-32: capture the already-rendered ACCESS view when no new model render arrives. */
    private fun captureAccessView(ctx: Context, view: ImageView, identity: CardIdentity, faceIdText: String?) {
        try {
            view.postDelayed({
                try {
                    val drawable = view.drawable ?: run {
                        noteAccessCapturePending(identity, "view-empty")
                        return@postDelayed
                    }
                    val w = drawable.intrinsicWidth
                    val h = drawable.intrinsicHeight
                    if (w <= 0 || h <= 0) {
                        noteAccessCapturePending(identity, "view-zero")
                        return@postDelayed
                    }
                    val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bitmap)
                    drawable.setBounds(0, 0, w, h)
                    drawable.draw(canvas)
                    val out = ByteArrayOutputStream()
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
                    bitmap.recycle()
                    val bytes = out.toByteArray()
                    val faceId = faceIdText?.toLongOrNull()
                    val ok = FaceCapture.saveModelSnapshot(identity.type, identity.id, bytes, faceIdText)
                    if (ok) {
                        ProviderClient.logOp("bridge", "original-ok", "${identity.type}:${identity.id} source=view bytes=${bytes.size}")
                        HLog.limWithSummary("access_original_ok", 6) {
                            "ACCESS original from view ${identity.fileKey} bytes=${bytes.size}"
                        }
                    } else {
                        noteAccessCapturePending(identity, "view-save")
                    }
                } catch (t: Throwable) {
                    noteAccessCapturePending(identity, t.javaClass.simpleName)
                }
            }, 350L)
        } catch (t: Throwable) {
            noteAccessCapturePending(identity, t.javaClass.simpleName)
        }
    }

    private fun noteAccessCapturePending(identity: CardIdentity, reason: String) {
        HLog.lim("access_capture_pending", 6) { "ACCESS original pending ${identity.fileKey} reason=$reason" }
        try {
            ProviderClient.logOp("access", "capture-pending", "${identity.type}:${identity.id} $reason")
        } catch (_: Throwable) {
        }
    }

    private fun hookAccessFace(cl: ClassLoader): MethodHook.Unhook? {
        val method = findWalletMethod(cl, CLASS_XI) { m ->
            m.name == "j" && m.parameterTypes.size >= 3 && m.parameterTypes[0] == ImageView::class.java
        } ?: run {
            HLog.w("xi#j not found — dumping methods")
            dumpMethods(cl, CLASS_XI, "j")
            return null
        }
        HLog.i("xi#j signature: ${signatureOf(method)}")
        return HookBridge.hookMethod(method, object : MethodHook() {
            override fun beforeHookedMethod(param: MethodHook.MethodHookParam?) {
                if (param == null) return
                try {
                    val args = param.args ?: return
                    val imageView = args.getOrNull(0) as? ImageView ?: return
                    val card = args.getOrNull(1) ?: return
                    val ctx = (args.getOrNull(2) as? Context) ?: imageView.context
                    if (isExcludedContext(ctx)) {
                        HLog.limWithSummary("xi_skip_custom", 3) { "xi#j skipped on ${ctx.javaClass.simpleName}" }
                        return
                    }
                    val identity = CardIdentityExtractor.of(card) ?: return
                    val faceIdText = readFieldString(card, "faceId")
                    val faceId = faceIdText?.toLongOrNull()
                    val url = readFieldString(card, "cardPicUrl")
                    val custom = faceId != null && (faceId >= 1000L || faceId <= -1000L)
                    val reportedUrl = if (custom) url?.takeIf { it.isNotBlank() && it != "null" } else null
                    accessModels[identity.fileKey] = WeakReference(card)
                    accessViews[identity.fileKey] = WeakReference(imageView)
                    recordCard(identity, reportedUrl, faceIdText)
                    HLog.limWithSummary("xi_record", 8) {
                        "xi#j access ${identity.fileKey} faceId=$faceIdText custom=$custom url=${reportedUrl?.take(60)}"
                    }

                    val resolved = ProviderClient.resolve(identity.type, identity.id)
                    if (resolved != null) {
                        param.setResult(null) // skip original load (void)
                        FaceEnforcer.register(imageView, resolved)
                        FaceImageLoader.load(imageView.context, resolved) { bitmap ->
                            if (bitmap != null) {
                                FaceEnforcer.applyBitmap(imageView, bitmap)
                                imageView.invalidate()
                            }
                        }
                        HLog.limWithSummary("xi_replace", 6) {
                            "xi#j replace ${identity.fileKey} v=${resolved.version} view=${CardIdentityExtractor.viewIdName(imageView)}"
                        }
                        return
                    }
                    val pendingRecapture = accessPendingCapture.remove(identity.fileKey)
                    if (pendingRecapture || FaceCapture.shouldCaptureModel(identity.type, identity.id, faceIdText)) {
                        HLog.limWithSummary("xi_capture_model", 6) {
                            "xi#j deterministic capture ${identity.fileKey} faceId=$faceIdText"
                        }
                        captureAccessModel(cl, ctx, identity, faceId, url, "model")
                    }
                } catch (t: Throwable) {
                    HLog.lim("xi_before_err", 3) { "xi#j before hook failed: ${t.javaClass.simpleName}: ${t.message}" }
                }
            }
        })
    }

    /** Round-25/32: deterministic door-face capture from AccessCard model fields (never drawable). */
    private fun captureAccessModel(
        cl: ClassLoader,
        ctx: Context,
        identity: CardIdentity,
        faceId: Long?,
        url: String?,
        reason: String
    ) {
        Thread({
            var bytes: ByteArray? = null
            var source = "none"
            try {
                val custom = faceId != null && (faceId >= 1000L || faceId <= -1000L)
                if (custom && !url.isNullOrBlank()) {
                    bytes = httpGetBytes(url)
                    if (bytes != null) source = "url"
                }
                if (bytes == null && faceId != null) {
                    val resId = resolveFaceRes(cl, faceId)
                    if (resId != 0) {
                        val bmp = try {
                            BitmapFactory.decodeResource(ctx.resources, resId)
                        } catch (_: Throwable) {
                            null
                        }
                        if (bmp != null) {
                            val out = ByteArrayOutputStream()
                            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
                            bmp.recycle()
                            bytes = out.toByteArray()
                            source = "resource"
                        }
                    }
                }
                if (bytes == null) {
                    bytes = findWalletCachedFace(ctx, identity, faceId)
                    if (bytes != null) source = "file"
                }
            } catch (_: Throwable) {
            }
            try {
                val ok = FaceCapture.saveModelSnapshot(identity.type, identity.id, bytes, faceId?.toString())
                if (ok) {
                    ProviderClient.logOp(
                        "bridge",
                        "original-ok",
                        "${identity.type}:${identity.id} source=$source bytes=${bytes?.size ?: 0}"
                    )
                } else {
                    noteAccessCapturePending(identity, "model-$source")
                }
                HLog.limWithSummary("xi_capture_model_ok", 6) {
                    "xi#j model snapshot ${identity.fileKey} ok=$ok source=$source bytes=${bytes?.size ?: 0} reason=$reason"
                }
            } catch (_: Throwable) {
            }
        }, "cardpersonality-door-capture").apply { isDaemon = true }.start()
    }

    /**
     * Round-32: wallet-native custom faces may also be cached in the wallet's private dirs. Read-only
     * scan of common face-cache locations, matched by faceId / card id / filename key.
     */
    private fun findWalletCachedFace(ctx: Context, identity: CardIdentity, faceId: Long?): ByteArray? {
        val dirs = listOf(
            java.io.File(ctx.filesDir, "cardface"),
            java.io.File(ctx.filesDir, "card_face"),
            java.io.File(ctx.filesDir, "accesscard"),
            java.io.File(ctx.cacheDir, "cardface"),
            java.io.File(ctx.cacheDir, "image_cache"),
            java.io.File(ctx.cacheDir, "glide"),
            java.io.File(ctx.filesDir, "cache")
        )
        val needles = listOfNotNull(
            faceId?.toString(),
            identity.id,
            identity.fileKey
        ).filter { it.length >= 3 }
        for (dir in dirs) {
            if (!dir.isDirectory) continue
            val files = try {
                dir.listFiles() ?: continue
            } catch (_: Throwable) {
                continue
            }
            for (f in files) {
                if (f.isDirectory) {
                    // one level of subdirectories (glide/... layouts)
                    try {
                        f.listFiles()?.forEach { sub ->
                            if (sub.isFile && matchesFaceNeedle(sub.name, needles) && sub.length() in 1024..(3L * 1024 * 1024)) {
                                return readImageBytes(sub)
                            }
                        }
                    } catch (_: Throwable) {
                    }
                    continue
                }
                if (!f.isFile) continue
                if (f.length() !in 1024..(3L * 1024 * 1024)) continue
                if (!matchesFaceNeedle(f.name, needles)) continue
                return readImageBytes(f)
            }
        }
        return null
    }

    private fun matchesFaceNeedle(name: String, needles: List<String>): Boolean {
        val n = name.lowercase()
        return needles.any { needle -> n.contains(needle.lowercase()) }
    }

    private fun readImageBytes(file: java.io.File): ByteArray? {
        return try {
            val bytes = file.readBytes()
            if (bytes.size < 4) return null
            val isJpeg = bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
            val isPng = bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()
            if (isJpeg) return bytes
            if (!isPng) return null
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val out = ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
            bmp.recycle()
            out.toByteArray()
        } catch (_: Throwable) {
            null
        }
    }

    /** f06.a(faceId, 0) -> bundled classic face resource id (door spec §2.1). */
    private fun resolveFaceRes(cl: ClassLoader, faceId: Long): Int {
        return try {
            val cls = Class.forName("f06", false, cl)
            val m = cls.getDeclaredMethod("a", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            m.isAccessible = true
            (m.invoke(null, faceId.toInt(), 0) as? Int) ?: 0
        } catch (_: Throwable) {
            0
        }
    }

    private fun httpGetBytes(url: String): ByteArray? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 12_000
                instanceFollowRedirects = true
                requestMethod = "GET"
            }
            try {
                if (conn.responseCode !in 200..299) return null
                conn.inputStream.use { it.readBytes() }
            } finally {
                conn.disconnect()
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** Reads a private AccessCard field without triggering the (possibly inlined) getter hooks. */
    private fun readFieldString(card: Any, fieldName: String): String? {
        return try {
            var c: Class<*>? = card.javaClass
            var depth = 0
            while (c != null && depth < 6) {
                try {
                    val f = c.getDeclaredField(fieldName)
                    f.isAccessible = true
                    return when (val v = f.get(card)) {
                        null -> null
                        is String -> v
                        else -> v.toString()
                    }
                } catch (_: NoSuchFieldException) {
                }
                c = c.superclass
                depth++
            }
            null
        } catch (_: Throwable) {
            null
        }
    }
    private fun hookEbikeFace(cl: ClassLoader): MethodHook.Unhook? {
        val method = findWalletMethod(cl, CLASS_PI3) { m ->
            m.name == "c" && m.parameterTypes.size >= 3 && m.parameterTypes[0] == ImageView::class.java
        } ?: run {
            HLog.w("pi3#c not found — dumping methods")
            dumpMethods(cl, CLASS_PI3, "c")
            return null
        }
        HLog.i("pi3#c signature: ${signatureOf(method)}")
        return HookBridge.hookMethod(method, safeAfter("pi3#c") { param ->
            val args = param.args ?: return@safeAfter
            val imageView = args.getOrNull(0) as? ImageView ?: return@safeAfter
            val ctx = (args.getOrNull(2) as? Context) ?: imageView.context
            if (isExcludedContext(ctx)) return@safeAfter
            val identity = CardIdentityExtractor.of(args.getOrNull(1)) ?: return@safeAfter
            recordCard(identity)
            val resolved = ProviderClient.resolve(identity.type, identity.id)
            if (resolved == null) {
                FaceCapture.schedule(imageView, identity)
                HLog.limWithSummary("xi_capture", 4) { "xi#j no config, capturing original ${identity.fileKey}" }
                return@safeAfter
            }
            FaceEnforcer.register(imageView, resolved)
            FaceImageLoader.load(imageView.context, resolved) { bitmap ->
                if (bitmap != null) {
                    FaceEnforcer.applyBitmap(imageView, bitmap)
                    imageView.invalidate()
                }
            }
            HLog.limWithSummary("pi3_replace", 6) {
                "pi3#c replace ${identity.fileKey} v=${resolved.version} view=${CardIdentityExtractor.viewIdName(imageView)}"
            }
        })
    }

    // ------------------------------------------------------------------ H13/H14 capture

    private fun hookStackAdapter(cl: ClassLoader): MethodHook.Unhook? {
        val method = findWalletMethod(cl, CLASS_STACK) { m ->
            m.name == "setAdapter" && m.parameterTypes.size == 1
        } ?: run {
            HLog.w("SwipeCardStackView#setAdapter not found")
            return null
        }
        return HookBridge.hookMethod(method, safeAfter("stack#setAdapter") { param ->
            stackRef = WeakReference(param.thisObject ?: return@safeAfter)
            stackAdapterRef = WeakReference(param.args?.getOrNull(0) ?: return@safeAfter)
            HLog.i("captured SwipeCardStackView + adapter for refresh")
        })
    }

    private fun hookPagerAdapter(cl: ClassLoader): MethodHook.Unhook? {
        val method = findWalletMethod(cl, CLASS_PAGER) { m ->
            m.name == "setAdapter" && m.parameterTypes.size == 1
        } ?: run {
            HLog.w("HwViewPager#setAdapter not found")
            return null
        }
        return HookBridge.hookMethod(method, safeAfter("pager#setAdapter") { param ->
            val pager = param.thisObject ?: return@safeAfter
            val adapter = param.args?.getOrNull(0) ?: return@safeAfter
            pagerRefs.add(WeakReference(pager) to WeakReference(adapter))
            if (pagerRefs.size > 12) pagerRefs.removeAt(0)
            HLog.limWithSummary("pager_capture", 8) { "captured HwViewPager + adapter (${pagerRefs.size})" }
        })
    }

    // ------------------------------------------------------------------ 卡包 list surfaces

    /**
     * S3 "卡包/我的卡片" list rows: to6#B(defpackage.yn0 holder, Object card).
     * The row face view is iv_icon (RoundedImageView, 6dp) and the holder's item view field is "a".
     */
    private fun hookMyCardsList(cl: ClassLoader): MethodHook.Unhook? {
        val method = findWalletMethod(cl, CLASS_TO6) { m ->
            m.name == "B" && m.parameterTypes.size == 2
        } ?: run {
            HLog.w("to6#B not found — dumping methods")
            dumpMethods(cl, CLASS_TO6, "B")
            return null
        }
        HLog.i("to6#B signature: ${signatureOf(method)}")
        return HookBridge.hookMethod(method, safeAfter("to6#B") { param ->
            val args = param.args ?: return@safeAfter
            val holder = args.getOrNull(0) ?: return@safeAfter
            val card = args.getOrNull(1) ?: cardObjectInHolder(holder)
            val root = viewRootOf(holder)
            val identity = CardIdentityExtractor.of(card)
                ?: cardObjectInHolder(holder)?.let { CardIdentityExtractor.of(it) }
                ?: root?.let { identityFromViewRoot(it) }
                ?: return@safeAfter
            recordCard(identity)
            HLog.limWithSummary("to6_B", 8) {
                "to6#B card=${identity.fileKey} configured=${ProviderClient.resolve(identity.type, identity.id) != null}"
            }
            root?.let { afterBind(it, identity) }
            reportIfDue()
        })
    }

    /** H4: mv1#c(ImageView, Object card) loads list/thumbnail faces; enforce ours after it. */
    private fun hookListThumbnails(cl: ClassLoader): MethodHook.Unhook? {
        val method = findWalletMethod(cl, CLASS_MV1) { m ->
            m.name == "c" && m.parameterTypes.size >= 2 && m.parameterTypes[0] == ImageView::class.java
        } ?: run {
            HLog.w("mv1#c not found — dumping methods")
            dumpMethods(cl, CLASS_MV1, "c")
            return null
        }
        HLog.i("mv1#c signature: ${signatureOf(method)}")
        return HookBridge.hookMethod(method, safeAfter("mv1#c") { param ->
            val args = param.args ?: return@safeAfter
            val imageView = args.getOrNull(0) as? ImageView ?: return@safeAfter
            val card = args.getOrNull(1)
            val identity = CardIdentityExtractor.of(card) ?: return@safeAfter
            recordCard(identity)
            val resolved = ProviderClient.resolve(identity.type, identity.id) ?: return@safeAfter
            FaceEnforcer.register(imageView, resolved)
            FaceImageLoader.load(imageView.context, resolved) { bitmap ->
                if (bitmap != null) {
                    FaceEnforcer.applyBitmap(imageView, bitmap)
                    imageView.invalidate()
                }
            }
            HLog.limWithSummary("mv1_c", 8) {
                "mv1#c thumb card=${identity.fileKey} v=${resolved.version} view=${CardIdentityExtractor.viewIdName(imageView)}"
            }
        })
    }
    // ------------------------------------------------------------------ detail adapters

    private fun hookDetailAdapter(cl: ClassLoader, className: String, methodName: String): MethodHook.Unhook? {
        val method = findWalletMethod(cl, className) { m ->
            m.name == methodName && m.parameterTypes.size == 2 &&
                m.parameterTypes[1] == Int::class.javaPrimitiveType
        } ?: run {
            HLog.w("$className#$methodName not found — dumping methods")
            dumpMethods(cl, className, methodName)
            return null
        }
        HLog.i("$className#$methodName signature: ${signatureOf(method)}")
        return HookBridge.hookMethod(method, safeAfter("$className#$methodName") { param ->
            val args = param.args ?: return@safeAfter
            val adapter = param.thisObject
            val position = args.getOrNull(1) as? Int ?: 0
            val root = args.getOrNull(0) as? View ?: adapter?.let { holderRootOf(it) }
            val identity = identityFromAdapter(adapter, position)
                ?: root?.let { identityFromViewRoot(it) }
            if (identity == null) {
                HLog.limWithSummary("detail_noid_$className", 5) {
                    "$className#$methodName no identity at position=$position"
                }
                return@safeAfter
            }
            recordCard(identity)
            val resolved = ProviderClient.resolve(identity.type, identity.id)
            HLog.limWithSummary("detail_$className", 8) {
                "$className#$methodName pos=$position card=${identity.fileKey} configured=${resolved != null}"
            }
            root?.let { afterBind(it, identity) }
            reportIfDue()
        })
    }

    // ------------------------------------------------------------------ ImageView guard

    private fun hookImageViewGuard(): MethodHook.Unhook? {
        val hook = object : MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
                try {
                    if (param == null) return
                    val imageView = param.thisObject as? ImageView ?: return
                    if (!FaceEnforcer.isRegistered(imageView)) return
                    val drawable = FaceEnforcer.ourDrawableFor(imageView) ?: return
                    param.args[0] = drawable
                } catch (t: Throwable) {
                    HLog.lim("iv_guard_err", 3) { "setImageDrawable guard failed: ${t.message}" }
                }
            }
        }
        return HookHelpers.findAndHookMethod(
            ImageView::class.java,
            "setImageDrawable",
            Drawable::class.java,
            hook,
        )
    }

    // ------------------------------------------------------------------ refresh

    @Volatile
    private var refreshHooksInstalled = false

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                if (intent?.action == ACTION_RECAPTURE) {
                    handleRecapture(context, intent)
                } else {
                    onConfigChanged()
                }
            } catch (t: Throwable) {
                HLog.e("CONFIG_CHANGED broadcast handler failed", t)
            }
        }
    }

    private fun ensureRefreshHooks(ctx: Context) {
        if (refreshHooksInstalled) return
        synchronized(this) {
            if (refreshHooksInstalled) return
            refreshHooksInstalled = true
            val main = Handler(Looper.getMainLooper())
            // 1) broadcast channel (app sends CONFIG_CHANGED)
            try {
                val filter = IntentFilter(ACTION_CONFIG_CHANGED)
                filter.addAction(ACTION_RECAPTURE)
                if (Build.VERSION.SDK_INT >= 33) {
                    ctx.registerReceiver(refreshReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    ctx.registerReceiver(refreshReceiver, filter)
                }
                HLog.i("refresh receiver registered (CONFIG_CHANGED)")
            } catch (t: Throwable) {
                HLog.w("refresh receiver registration failed: ${t.javaClass.simpleName}: ${t.message}")
            }
            // 2) content observer channel (app calls notifyChange on content://authority/config)
            try {
                val observer = object : ContentObserver(main) {
                    override fun onChange(selfChange: Boolean, uri: Uri?) {
                        try {
                            onConfigChanged()
                        } catch (t: Throwable) {
                            HLog.e("CONFIG_CHANGED observer handler failed", t)
                        }
                    }
                }
                ctx.contentResolver.registerContentObserver(Uri.parse(CONFIG_URI), true, observer)
                HLog.i("refresh content observer registered ($CONFIG_URI)")
            } catch (t: Throwable) {
                HLog.w("content observer registration failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    private fun onConfigChanged() {
        lastConfigChangeAt = System.currentTimeMillis()
        HLog.i("CONFIG_CHANGED: refreshing faces")
        FaceImageLoader.invalidate()
        // ANR fix v14: forced provider IPC must never run on the wallet main thread.
        try {
            ProviderClient.refreshAsync {
                try {
                    // Clear wallet-side preload/cache maps so stale bitmaps cannot win.
                    clearWalletCaches()
                    reloadRegisteredViews()
                    rebindHomeStack()
                    rebindDetailPagers()
                    // Round-9: config just arrived — finish cold-start pending faces first.
                    retryPendingFaces()
                    // Round-7: make element hide/unhide take effect on already-open pages too.
                    reapplyElementHides()
                    HLog.i("CONFIG_CHANGED: refresh issued (replaced=$replacedCount)")
                } catch (t: Throwable) {
                    HLog.e("CONFIG_CHANGED refresh failed", t)
                }
            }
        } catch (t: Throwable) {
            HLog.e("CONFIG_CHANGED scheduling failed", t)
        }
    }

    private fun clearWalletCaches() {
        clearStaticMap(CLASS_HV1, "c")
        clearStaticMap(CLASS_CACHE_MV1, "c")
    }

    private fun clearStaticMap(className: String, fieldName: String) {
        try {
            val cl = walletClassLoader ?: return
            val cls = Class.forName(className, false, cl)
            val field = cls.declaredFields.firstOrNull { it.name == fieldName } ?: return
            if (!java.lang.reflect.Modifier.isStatic(field.modifiers)) return
            field.isAccessible = true
            val value = field.get(null)
            if (value is MutableMap<*, *>) {
                value.clear()
                HLog.i("cleared $className#$fieldName (${value.size} after clear)")
            }
        } catch (t: Throwable) {
            HLog.lim("clear_cache_$className", 2) {
                "clear $className#$fieldName failed: ${t.javaClass.simpleName}: ${t.message}"
            }
        }
    }

    private fun reloadRegisteredViews() {
        val views = FaceEnforcer.registeredViews()
        HLog.i("CONFIG_CHANGED: ${views.size} registered view(s)")
        for ((view, oldFace) in views) {
            try {
                val face = ProviderClient.resolveFileKey(oldFace.fileKey) ?: continue
                FaceEnforcer.register(view, face)
                FaceImageLoader.load(view.context, face) { bitmap ->
                    if (bitmap != null) {
                        FaceEnforcer.applyBitmap(view, bitmap)
                        view.invalidate()
                    }
                }
            } catch (t: Throwable) {
                HLog.w("refresh view failed: ${t.message}")
            }
        }
    }

    /** H13: full rebuild of the swipe stack so every holder re-binds (p/nh5 run again). */
    private fun rebindHomeStack() {
        val stack = stackRef?.get() ?: return
        try {
            val method = stack.javaClass.methods.firstOrNull { it.name == "m" && it.parameterCount == 0 }
            if (method != null) {
                method.isAccessible = true
                method.invoke(stack)
                HLog.i("refreshed home swipe stack via SwipeCardStackView#m()")
                return
            }
        } catch (t: Throwable) {
            HLog.w("stack#m() failed: ${t.javaClass.simpleName}: ${t.message}")
        }
        try {
            val adapter = stackAdapterRef?.get() ?: return
            val listField = adapter.javaClass.declaredFields.firstOrNull {
                java.util.List::class.java.isAssignableFrom(it.type) && !java.lang.reflect.Modifier.isStatic(it.modifiers)
            }
            val fMethod = adapter.javaClass.methods.firstOrNull {
                it.name == "f" && it.parameterCount == 1 &&
                    java.util.List::class.java.isAssignableFrom(it.parameterTypes[0])
            }
            if (listField != null && fMethod != null) {
                listField.isAccessible = true
                val list = listField.get(adapter) as? Collection<*> ?: return
                fMethod.isAccessible = true
                fMethod.invoke(adapter, CopyOnWriteArrayList(list))
                HLog.i("refreshed home swipe adapter via z79#f(list)")
            }
        } catch (t: Throwable) {
            HLog.w("adapter#f(list) refresh failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** H14: re-set the same adapter on captured detail pagers (full page rebuild). */
    private fun rebindDetailPagers() {
        for ((pagerRef, adapterRef) in pagerRefs) {
            val pager = pagerRef.get() ?: continue
            val adapter = adapterRef.get() ?: continue
            try {
                val method = pager.javaClass.methods.firstOrNull {
                    it.name == "setAdapter" && it.parameterCount == 1
                } ?: continue
                method.isAccessible = true
                method.invoke(pager, adapter)
                HLog.limWithSummary("pager_refresh", 5) {
                    "refreshed detail pager ${pager.javaClass.simpleName}"
                }
            } catch (t: Throwable) {
                HLog.w("pager refresh failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    // ------------------------------------------------------------------ face application helpers

    private fun applyFaceToRoot(root: View, identity: CardIdentity, scheduleRetries: Boolean = true) {
        val resolved = ProviderClient.resolve(identity.type, identity.id)
        if (resolved == null) {
            // Cold-start race: config/provider is not ready yet. Remember the root and retry soon;
            // otherwise the page would keep the original face until it happens to re-bind.
            try {
                pendingFaceRoots[root] = identity
            } catch (_: Throwable) {
            }
            if (scheduleRetries) schedulePendingFaceRetries(identity)
            HLog.limWithSummary("face_pending_${identity.fileKey}", 6) {
                "face pending (config not ready) card=${identity.fileKey} root=${root.javaClass.simpleName}"
            }
            return
        }
        try {
            pendingFaceRoots.remove(root)
        } catch (_: Throwable) {
        }
        val views = collectFaceImageViews(root)
        if (views.isEmpty()) return
        for ((view, _) in views) {
            FaceEnforcer.register(view, resolved)
            FaceImageLoader.load(view.context, resolved) { bitmap ->
                if (bitmap != null) {
                    FaceEnforcer.applyBitmap(view, bitmap)
                    view.invalidate()
                }
            }
        }
        HLog.limWithSummary("apply_${identity.fileKey}", 5) {
            "applied face ${identity.fileKey} v=${resolved.version} to ${views.size} view(s): " +
                views.joinToString(",") { it.second }
        }
        ProviderClient.logOp(
            "hook",
            "apply",
            "${identity.fileKey} v=${resolved.version} views=${views.size}",
        )
    }

    private fun schedulePendingFaceRetries(identity: CardIdentity) {
        for (delay in FACE_RETRY_DELAYS_MS) {
            try {
                mainHandler.postDelayed({
                    try {
                        retryPendingFaces()
                    } catch (_: Throwable) {
                    }
                }, delay)
            } catch (_: Throwable) {
            }
        }
    }

    /** Re-applies custom faces to still-attached pending roots once config becomes available. */
    private fun retryPendingFaces() {
        val snapshot: List<Pair<View, CardIdentity>> = try {
            synchronized(pendingFaceRoots) {
                pendingFaceRoots.entries.mapNotNull { entry ->
                    val r = entry.key
                    val i = entry.value
                    if (r.isAttachedToWindow) r to i else null
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
        var applied = 0
        for ((root, identity) in snapshot) {
            try {
                if (ProviderClient.resolve(identity.type, identity.id) != null) {
                    applyFaceToRoot(root, identity, scheduleRetries = false)
                    applied++
                }
            } catch (_: Throwable) {
            }
        }
        if (snapshot.isNotEmpty()) {
            HLog.limWithSummary("face_retry", 6) {
                "pending face retry: ${snapshot.size} root(s), applied=$applied"
            }
        }
    }

    /** Requirement 3: element KEEP/HIDE applied after each bind (whitelisted ids only). */
    /**
     * Hides user-selected app element ids (KEEP is default; SAFE_HIDE_IDS whitelist only).
     *
     * Round-7 fix: the label may live OUTSIDE the adapter's item/holder root (fragment/activity
     * content view), and the wallet may re-set visibility after data loads. So we:
     *  - resolve the target in the bound root, its ancestors AND the full window root;
     *  - apply immediately AND on delayed passes (150/400/900 ms) to survive later data loads;
     *  - re-apply on CONFIG_CHANGED via [reapplyElementHides].
     */
    private fun applyElements(root: View, identity: CardIdentity) {
        val hidden = ProviderClient.hiddenElements(identity.type, identity.id)
        if (hidden.isEmpty()) return
        applyHidePass(root, identity, hidden, 0L)
        for (delay in HIDE_RETRY_DELAYS_MS) {
            try {
                mainHandler.postDelayed({
                    try {
                        applyHidePass(root, identity, hidden, delay)
                    } catch (t: Throwable) {
                        HLog.lim("hide_pass_err", 3) {
                            "element hide pass failed delay=$delay: ${t.javaClass.simpleName}"
                        }
                    }
                }, delay)
            } catch (_: Throwable) {
            }
        }
    }

    private fun applyHidePass(root: View, identity: CardIdentity, hidden: List<String>, delayMs: Long) {
        val topRoot: View? = try {
            root.rootView
        } catch (_: Throwable) {
            null
        }
        for (idName in hidden) {
            if (idName !in SAFE_HIDE_IDS) {
                if (delayMs == 0L) {
                    HLog.lim("hide_skip_$idName", 2) { "element hide skipped (not whitelisted): $idName" }
                }
                continue
            }
            val found = findHideTarget(root, topRoot, idName)
            if (found == null) {
                HLog.limWithSummary("hide_miss_$idName", 6) {
                    "element hide target NOT found id=$idName card=${identity.fileKey} " +
                        "root=${root.javaClass.simpleName} top=${topRoot?.javaClass?.simpleName ?: "null"} delay=$delayMs"
                }
                continue
            }
            val was = found.visibility
            if (was != View.GONE) {
                found.visibility = View.GONE
                try { ownerHiddenViews[found] = idName } catch (_: Throwable) {}
                HLog.limWithSummary("hide_$idName", 8) {
                    "element HIDE $idName (${found.javaClass.simpleName}, was=$was) " +
                        "card=${identity.fileKey} root=${root.javaClass.simpleName} delay=$delayMs"
                }
            } else if (delayMs == 0L) {
                HLog.limWithSummary("hide_${idName}_already", 4) {
                    "element already GONE id=$idName card=${identity.fileKey} root=${root.javaClass.simpleName}"
                }
            }
        }
    }

    /**
     * Resolves an element id in the bound root, any ancestor view, and finally the whole window
     * (decor/content view) — the wallet inflates some labels outside the list item subtree.
     */
    private fun findHideTarget(root: View, topRoot: View?, name: String): View? {
        val id = elementViewId(root, name)
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
        var parent: View? = root.parent as? View
        var depth = 0
        while (parent != null && depth < 12) {
            try {
                parent.findViewById<View>(id)?.let { return it }
            } catch (_: Throwable) {
            }
            parent = parent.parent as? View
            depth++
        }
        return null
    }

    /** Resolves an "id/<name>" resource in the wallet package (with fallbacks). */
    private fun elementViewId(root: View, name: String): Int {
        val candidates = try {
            listOf(root.context.packageName, root.context.resources.getResourcePackageName(android.R.id.content))
        } catch (_: Throwable) {
            listOf(root.context.packageName)
        }
        for (pkg in candidates.distinct()) {
            try {
                val id = root.resources.getIdentifier(name, "id", pkg)
                if (id != 0) return id
            } catch (_: Throwable) {
            }
        }
        return try {
            root.resources.getIdentifier(name, "id", "com.hihonor.wallet")
        } catch (_: Throwable) {
            0
        }
    }

    /**
     * CONFIG_CHANGED: re-apply element hides to every still-attached bound root (and the foreground
     * window), so toggling 隐藏 in the panel takes effect on already-open wallet pages.
     */
    private fun reapplyElementHides() {
        val snapshot: List<Pair<View, CardIdentity>> = try {
            synchronized(boundRoots) {
                boundRoots.entries.mapNotNull { entry ->
                    val r = entry.key
                    val i = entry.value
                    if (r.isAttachedToWindow) r to i else null
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
        restoreUnhiddenElements()
        var appliedRoots = 0
        for ((root, identity) in snapshot) {
            try {
                applyElements(root, identity)
                appliedRoots++
            } catch (_: Throwable) {
            }
        }
        try {
            val activity = activityOf(snapshot.firstOrNull()?.first?.context)
            val decor = activity?.window?.decorView
            if (decor != null) {
                for (info in detected.values) {
                    val hidden = ProviderClient.hiddenElements(info.type, info.key.id)
                    if (hidden.isNotEmpty()) {
                        applyHidePass(
                            decor,
                            CardIdentity(info.type, info.key.id, info.name, info.subtitle),
                            hidden,
                            0L,
                        )
                    }
                }
            }
        } catch (_: Throwable) {
        }
        HLog.limWithSummary("hide_reapply", 5) {
            "element hide re-applied to $appliedRoots bound root(s) + foreground window"
        }
    }

    /**
     * Restores visibility of elements WE hid earlier that are no longer in any configured hide
     * list (KEEP has always been the default). Lets "取消隐藏" take effect without a re-bind.
     */
    private fun restoreUnhiddenElements() {
        val stillHidden = HashSet<String>()
        try {
            for (info in detected.values) {
                stillHidden.addAll(ProviderClient.hiddenElements(info.type, info.key.id))
            }
        } catch (_: Throwable) {
        }
        val restored = ArrayList<Pair<View, String>>()
        try {
            synchronized(ownerHiddenViews) {
                val it = ownerHiddenViews.entries.iterator()
                while (it.hasNext()) {
                    val e = it.next()
                    val v = e.key
                    val idName = e.value
                    if (idName !in stillHidden) {
                        restored.add(v to idName)
                        it.remove()
                    }
                }
            }
        } catch (_: Throwable) {
        }
        for ((view, idName) in restored) {
            try {
                if (view.isAttachedToWindow && view.visibility == View.GONE) {
                    view.visibility = View.VISIBLE
                    HLog.limWithSummary("unhide_$idName", 6) {
                        "element UNHIDE $idName (${view.javaClass.simpleName}) — no longer configured"
                    }
                }
            } catch (_: Throwable) {
            }
        }
        if (restored.isNotEmpty()) {
            HLog.i("element hide: restored ${restored.size} view(s)")
        }
    }
    /** Activity from a context chain (used for window-wide element hiding). */
    private fun activityOf(ctx: Context?): Activity? {
        var current = ctx
        var depth = 0
        while (current != null && depth < 8) {
            if (current is Activity) return current
            current = (current as? ContextWrapper)?.baseContext
            depth++
        }
        return null
    }

    private fun afterBind(root: View, identity: CardIdentity) {
        try {
            synchronized(boundRoots) { boundRoots[root] = identity }
        } catch (_: Throwable) {
        }
        try {
            applyFaceToRoot(root, identity)
        } catch (t: Throwable) {
            HLog.e("applyFaceToRoot failed", t)
        }
        try {
            applyElements(root, identity)
        } catch (t: Throwable) {
            HLog.e("applyElements failed", t)
        }
        try {
            if (ProviderClient.resolve(identity.type, identity.id) == null) {
                for ((view, _) in collectFaceImageViews(root)) {
                    FaceCapture.schedule(view, identity)
                }
            }
        } catch (t: Throwable) {
            HLog.lim("capture_bind_err", 3) { "afterBind capture failed: ${t.javaClass.simpleName}" }
        }
        try {
            ensureRefreshHooks(root.context)
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------ view/identity helpers

    private fun isExcludedContext(ctx: Context): Boolean {
        val name = activityNameOf(ctx) ?: ctx.javaClass.simpleName
        return EXCLUDED_CONTEXTS.any { name.contains(it) }
    }

    private fun activityNameOf(ctx: Context): String? {
        var current: Context? = ctx
        var depth = 0
        while (current != null && depth < 8) {
            if (current is Activity) return current.javaClass.simpleName
            current = (current as? ContextWrapper)?.baseContext
            depth++
        }
        return null
    }

    private fun identityFromView(view: ImageView): CardIdentity? {
        CardIdentityExtractor.of(view.tag)?.let { return it }
        var parent = view.parent
        var depth = 0
        while (parent != null && depth < 6) {
            if (parent is View) {
                CardIdentityExtractor.of(parent.tag)?.let { return it }
            }
            parent = (parent as? View)?.parent
            depth++
        }
        return null
    }

    private fun viewRootOf(holder: Any): View? {
        var best: View? = null
        var clazz: Class<*>? = holder.javaClass
        var depth = 0
        while (clazz != null && depth < 6) {
            for (field in clazz.declaredFields) {
                try {
                    if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                    field.isAccessible = true
                    val value = field.get(holder) as? View ?: continue
                    best = when {
                        best == null -> value
                        value is ViewGroup && (best !is ViewGroup || value.childCount > best.childCount) -> value
                        else -> best
                    }
                } catch (_: Throwable) {
                }
            }
            clazz = clazz.superclass
            depth++
        }
        return best
    }

    private fun holderRootOf(holder: Any): View? = viewRootOf(holder)

    private fun cardObjectInHolder(holder: Any): Any? {
        var clazz: Class<*>? = holder.javaClass
        var depth = 0
        while (clazz != null && depth < 6) {
            for (field in clazz.declaredFields) {
                try {
                    if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                    field.isAccessible = true
                    val value = field.get(holder) ?: continue
                    if (value is View) continue
                    if (CardIdentityExtractor.typeOf(value.javaClass) != null) return value
                } catch (_: Throwable) {
                }
            }
            clazz = clazz.superclass
            depth++
        }
        return null
    }

    private fun identityFromViewRoot(root: View): CardIdentity? {
        CardIdentityExtractor.of(root.tag)?.let { return it }
        for ((view, _) in collectFaceImageViews(root)) {
            CardIdentityExtractor.of(view.tag)?.let { return it }
        }
        return null
    }

    private fun identityFromAdapter(adapter: Any?, position: Int): CardIdentity? {
        adapter ?: return null
        var clazz: Class<*>? = adapter.javaClass
        var depth = 0
        while (clazz != null && depth < 6) {
            for (field in clazz.declaredFields) {
                try {
                    if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                    if (!java.util.List::class.java.isAssignableFrom(field.type)) continue
                    field.isAccessible = true
                    val list = field.get(adapter) as? List<*> ?: continue
                    if (position < 0 || position >= list.size) continue
                    CardIdentityExtractor.of(list[position])?.let { return it }
                } catch (_: Throwable) {
                }
            }
            clazz = clazz.superclass
            depth++
        }
        return null
    }

    private fun collectFaceImageViews(root: View): List<Pair<ImageView, String>> {
        val out = ArrayList<Pair<ImageView, String>>()
        val queue = ArrayDeque<View>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 200) {
            val view = queue.removeFirst()
            visited++
            if (view is ImageView) {
                val name = CardIdentityExtractor.viewIdName(view)
                if (isFaceIdName(name)) out.add(view to name)
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    view.getChildAt(i)?.let { queue.add(it) }
                }
            }
        }
        return out
    }

    private fun isFaceIdName(name: String): Boolean = FACE_VIEW_IDS.any { name.contains(it, ignoreCase = true) }

    // ------------------------------------------------------------------ reporting / diagnostics

    /**
     * Records a detected card. [faceUrl] is only ever supplied by the face-source getter hook
     * (from param.getResult()); bind paths pass null and never invoke face getters.
     */
    private fun recordCard(identity: CardIdentity, faceUrl: String? = null, nativeFaceKey: String? = null) {
        val previous = detected[identity.fileKey]
        detected[identity.fileKey] = CardInfo(
            key = com.cardpersonality.honor.data.CardKey(identity.type, identity.id),
            name = identity.name,
            subtitle = identity.subtitle,
            lastSeen = System.currentTimeMillis(),
            appElementIds = emptyList(),
            faceUrl = faceUrl ?: previous?.faceUrl,
            nativeFaceKey = nativeFaceKey ?: previous?.nativeFaceKey,
        )
    }

    /**
     * Normalizes the raw value returned by a wallet face getter into the reported faceUrl:
     * transit/bank/campus/carkey URLs pass through; access has no URL; eBike only for faceId>=1000.
     */
    private fun normalizeFaceUrlFromResult(type: String, card: Any?, raw: String?): String? {
        val v = raw?.trim()
        if (v.isNullOrEmpty() || v == "null" || v == "0") return null
        return try {
            when (type) {
                com.cardpersonality.honor.data.CardTypes.ACCESS -> null
                com.cardpersonality.honor.data.CardTypes.EBIKE -> {
                    val faceId = CardIdentityExtractor.faceIdOf(card)
                    if (faceId != null && faceId < 1000L) null else v
                }
                else -> v
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun reportIfDue(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val last = lastReportAt.get()
        if (!force && now - last < 10_000L) return
        if (!lastReportAt.compareAndSet(last, now)) return
        if (detected.isEmpty()) return
        val arr = JSONArray()
        for (info in detected.values) {
            try {
                val o = info.toJson()
                // Round-10: wallet dimension in the report (panel groups cards per wallet).
                o.put("wallet", ProviderClient.walletPackage)
                o.put("walletKind", Wallets.kindOf(ProviderClient.walletPackage))
                o.put("walletLabel", Wallets.labelOf(ProviderClient.walletPackage))
                arr.put(o)
            } catch (_: Throwable) {
            }
        }
        ProviderClient.reportAsync(arr.toString()) // never block the wallet main thread
        HLog.limWithSummary("report", 5) { "report queued for ${detected.size} cards" }
    }

    /** Known "face source URL" getters per wallet model class (used for URL->card mapping). */
    private val FACE_SOURCE_GETTERS = listOf(
        "com.hihonor.wallet.serviceinterface.trafficcard.TransitCard" to "getFaceImage",
        "com.hihonor.wallet.serviceinterface.campuscard.CampusCard" to "getCardFaceImage",
        "com.hihonor.wallet.serviceinterface.bankcard.BankCard" to "getCardPic",
        "com.hihonor.cloud.common.context.BleDkBean" to "getImageSelected",
        "com.hihonor.wallet.serviceinterface.accesscard.AccessCard" to "getCardPicUrl",
    )

    /**
     * Hooks the model face-source getters (diagnostic + URL->configured-face registry).
     *
     * Why: several surfaces (lyra home-card la8, detail pages v8a, notifications) read the face
     * URL from the model and pass it straight to nh5#c — they never go through nn0#p, so the
     * ThreadLocal current card is empty. Registering URL -> configured face here lets H2 replace
     * those loads deterministically.
     */
    private fun hookFaceSourceGetters(cl: ClassLoader): MethodHook.Unhook? {
        var any: MethodHook.Unhook? = null
        for ((className, methodName) in FACE_SOURCE_GETTERS) {
            try {
                val cls = Class.forName(className, false, cl)
                val method = cls.declaredMethods.firstOrNull {
                    it.name == methodName && it.parameterTypes.isEmpty()
                } ?: continue
                val unhook = HookBridge.hookMethod(method, safeAfter("$className#$methodName") { param ->
                    if (inFaceSourceHook.get() == true) {
                        val n = faceSourceReentries.incrementAndGet()
                        if (n == 1L || n % 100L == 0L) {
                            HLog.w("face-getter hook re-entered; ignored (guard n=$n)")
                        }
                        return@safeAfter
                    }
                    inFaceSourceHook.set(true)
                    try {
                        // ANR fix: NEVER reflectively invoke any getter here (that would re-enter the
                        // hooked getter). The URL comes from the hook result; identity extraction is
                        // restricted to non-face getters (includeFaceUrl = false).
                        val original = param.getResult() as? String
                        val receiver = param.thisObject
                        val identity = CardIdentityExtractor.of(receiver, includeFaceUrl = false)
                        if (identity != null && !original.isNullOrEmpty()) {
                            sourceToIdentity[original] = identity
                            val faceUrl = normalizeFaceUrlFromResult(identity.type, receiver, original)
                            if (faceUrl != null) recordCard(identity, faceUrl)
                        }
                        // Cached-only resolve: never performs provider IPC on this (main) thread.
                        val resolved = identity?.let { ProviderClient.resolve(it.type, it.id) }
                        if (resolved != null && !original.isNullOrEmpty()) {
                            sourceToFace[original] = resolved
                            HLog.limWithSummary("face_url_map", 10) {
                                "mapped face URL -> ${resolved.fileKey} v=${resolved.version} (${identity?.fileKey})"
                            }
                        }
                        HLog.limWithSummary("face_getter", 10) {
                            "$className#$methodName card=${identity?.fileKey} original=${original?.take(120)} " +
                                "configured=${resolved != null}"
                        }
                    } finally {
                        inFaceSourceHook.remove()
                    }
                })
                HLog.i("hooked $className#$methodName (face source getter)")
                if (unhook != null) any = unhook
            } catch (t: Throwable) {
                HLog.w("face source getter $className#$methodName unavailable: ${t.javaClass.simpleName}")
            }
        }
        return any
    }


    private fun dumpMethods(cl: ClassLoader, className: String, nameHint: String) {
        try {
            val cls = Class.forName(className, false, cl)
            val methods = cls.declaredMethods
                .filter { it.name == nameHint }
                .joinToString("\n  ") { signatureOf(it) }
            HLog.i("$className methods named '$nameHint':\n  $methods")
        } catch (t: Throwable) {
            HLog.w("dumpMethods failed for $className", t)
        }
    }

    private fun signatureOf(m: java.lang.reflect.Method): String =
        "${m.name}(${m.parameterTypes.joinToString(",") { it.name }})"

    private val FACE_VIEW_IDS = listOf(
        "iv_card_bg", "iv_traffic_card_bg", "iv_card_face", "iv_bank_card_face",
        "image_dk_card_face", "iv_card_img", "iv_ebike_card_bg", "iv_split_card_bg",
    )
}