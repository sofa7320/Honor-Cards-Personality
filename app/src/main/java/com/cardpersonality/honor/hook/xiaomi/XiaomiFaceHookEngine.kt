package com.cardpersonality.honor.hook.xiaomi

import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.cardpersonality.honor.data.CardTypes
import com.cardpersonality.honor.hook.CardIdentity
import com.cardpersonality.honor.hook.HLog
import com.cardpersonality.honor.hook.ProviderClient
import com.cardpersonality.honor.hook.Wallets
import com.cardpersonality.honor.hook.api.HookBridge
import com.cardpersonality.honor.hook.api.MethodHook
import com.cardpersonality.honor.hook.hookLogged
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Method
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
 * Xiaomi smart-card (小米智能卡, `com.miui.tsmclient`) card-face engine — NEW in 0.2.13.
 *
 * Ported from the proven GPL-3.0 reference module `参考仓库/cardface-miuix-main`
 * (`xposed/HookEngine.kt`, `MethodLocator.kt`, `UrlMapper.kt`). The reference replaces a card face by
 * compositing a 960x606 PNG and handing the wallet's Glide chain a `file://` path that lives in the
 * wallet's own private directory; it discovers these hook points:
 *
 *   slot 0  `com.miui.tsmclient.util` / source file `CustomGlideUrl.java`
 *           -> a **static `Object x(String)`-style factory** that wraps a face URL for Glide.
 *              The reference hooks it and returns the replacement URL directly (primary path).
 *   slot 1  `com.bumptech.glide.RequestManager` (source `RequestManager.java`)
 *           -> single-argument `load(...)` overloads; the reference replaces the argument.
 *   slot 10 `com.miui.tsmclient.util`, method `String x(CardInfo)` -> super-island face URL.
 *
 * We cannot use DexKit (the reference's finder) without adding a native dependency to this module,
 * so the same targets are located with plain reflection:
 *  - Glide classes have stable names (`com.bumptech.glide.RequestManager` / `RequestBuilder`);
 *  - the obfuscated `CustomGlideUrl`-like factory is found by **scanning the wallet's own dex class
 *    names** for `com.miui.tsmclient.util.*` (see [dexClassNames]) and probing for the exact
 *    reference signature: `static (String) -> Object|String`;
 *  - `CardInfo` observers are found the same way (any util method with a
 *    `com.miui.tsmclient.entity.CardInfo` parameter); they give us real card names/types for the
 *    report when available, otherwise the card is identified from its face URL.
 *
 * Face replacement: the panel renders the JPEG and pushes it over the loopback bridge
 * ([XiaomiBridge]); the hook writes it into the wallet's own `files/cardpersonality/faces/`
 * directory **in-process** (the injected module runs with the wallet's uid), so unlike the reference
 * no root shell is required for the copy. The replacement handed to Glide is a `file://` URL — the
 * exact scheme the reference validates on device.
 *
 * Card enumeration: cards are recorded from intercepted face URLs (the reference's model: a card is
 * its face URL). Identity/type/name are derived as follows:
 *  - identity: stable `cardId`/`id`-style query parameter when present, else a hash of the URL after
 *    dropping volatile segments (size/hash/UUID path segments and resize/signature query keys —
 *    same rules as the reference `UrlMapper.normalizedUrl`);
 *  - type: query/path hints (`door` -> access, `bank` -> bank, `campus` -> campus, ...) with the
 *    default `transit`; a `CardInfo` observer overrides it when available;
 *  - name: `CardInfo` name when available, else a readable URL basename, else 小米+type label.
 *
 * Fail-open by construction: any failure leaves the wallet's original URL untouched. No Xiaomi device
 * is available in this environment — static-only port, see work/re/xiaomi/XIAOMI-PORT.md.
 */
object XiaomiFaceHookEngine {
    const val WALLET_PACKAGE = "com.miui.tsmclient"

    private const val MODULE_VERSION = "0.2.13"
    private const val MAX_LOG_LINES = 24

    private val handles = CopyOnWriteArrayList<MethodHook.Unhook>()
    @Volatile private var installed = false
    @Volatile private var armed = false
    @Volatile private var walletPackage: String = WALLET_PACKAGE

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cardpersonality-xiaomi-worker").apply { isDaemon = true }
    }

    // detected cards: "<pkg>|<type>:<identity>" -> report JSON object
    private val detected = ConcurrentHashMap<String, JSONObject>()
    private val urlToIdentity = ConcurrentHashMap<String, CardIdentity>()
    private val metaById = ConcurrentHashMap<String, CardMeta>()

    private val observedUrls = AtomicLong(0L)
    private val recordedCards = AtomicLong(0L)
    private val replacedUrls = AtomicLong(0L)
    private val glideHooks = AtomicLong(0L)
    private val factoryHooks = AtomicLong(0L)
    private val cardInfoHooks = AtomicLong(0L)
    private val skippedNonFace = AtomicLong(0L)
    private val lastReportAt = AtomicLong(0L)
    private val attemptedHooks = AtomicLong(0L)
    private val installedHooks = AtomicLong(0L)
    private val missedTargets = CopyOnWriteArrayList<String>()
    private val hitLogged = ConcurrentHashMap<String, AtomicBoolean>()

    @Volatile private var processName: String = ""
    @Volatile private var isMainProcess: Boolean = false
    @Volatile private var classLoaderName: String = ""
    @Volatile private var lastHookReportAt: Long = 0L
    @Volatile private var heartbeatSent: Boolean = false
    private val cardInfoObserved = AtomicLong(0L)

    /** CardInfo class name (stable entity name; not obfuscated in the reference module's findings). */
    private const val CARD_INFO_CLASS = "com.miui.tsmclient.entity.CardInfo"

    /** Glide classes whose single-argument `load(...)` methods are the generic interception point. */
    private val GLIDE_HOOK_CLASSES = listOf(
        "com.bumptech.glide.RequestManager",
        "com.bumptech.glide.RequestBuilder",
    )

    /** Package scanned in the wallet dex for the two obfuscated MIUI targets. */
    private val MIUI_UTIL_PACKAGE = "com.miui.tsmclient.util"
    private val MIUI_PACKAGE_PREFIX = "com.miui.tsmclient"

    private data class CardMeta(val name: String, val type: String?)

    private data class ObservedCard(
        val id: String?,
        val name: String,
        val type: String?,
        val url: String?,
    )

    // ================================================================ install

    fun install(classLoader: ClassLoader, processName: String?, pkg: String = WALLET_PACKAGE) {
        if (installed) return
        installed = true
        walletPackage = pkg
        this.processName = processName ?: ""
        classLoaderName = classLoader.toString()
        isMainProcess = this.processName.isEmpty() ||
            this.processName == walletPackage ||
            this.processName == "$walletPackage:main"
        try {
            ProviderClient.walletPackage = walletPackage
            XiaomiBridge.start(walletPackage) { engineStateJson() }
            XiaomiBridge.setConfigListener { onConfigChanged() }
            XiaomiBridge.setConnectedListener { forceReportNow() }
            XiaomiBridge.setCardSnapshotSupplier { cardsJson() }
            XiaomiBridge.setOriginalInvalidatedListener { _, _ -> /* no bitmap cache in this engine */ }
        } catch (_: Throwable) {
        }
        try {
            sendHeartbeat("install")
        } catch (_: Throwable) {
        }
        try {
            // Fast path: hook Glide synchronously (its class names are stable and it is the hook that
            // keeps the wallet's first card page working). The dex scan for the obfuscated MIUI
            // targets is deliberately moved off the wallet startup thread (see worker below).
            hookGlide(classLoader)
            armed = handles.isNotEmpty()
            HLog.i("Xiaomi engine: glideHooks=${glideHooks.get()} proc=$processName default=$isMainProcess")
            XiaomiBridge.oplog(
                "armed",
                "proc=$processName default=$isMainProcess glideHooks=${glideHooks.get()} " +
                    "attempted=${attemptedHooks.get()} installed=${installedHooks.get()}",
            )
        } catch (t: Throwable) {
            HLog.e("Xiaomi engine install failed (fail-open)", t)
            XiaomiBridge.oplog("init-fail", t.javaClass.simpleName + ": " + t.message)
        }
        // Slow path: DexKit-free dex scan for the obfuscated MIUI targets (bounded), one retry.
        worker.execute {
            try {
                Thread.sleep(1_200L)
                hookMiuiTargets(classLoader)
                if (factoryHooks.get() == 0L || cardInfoHooks.get() == 0L) {
                    Thread.sleep(3_000L)
                    hookMiuiTargets(classLoader)
                }
            } catch (_: Throwable) {
            } finally {
                armed = handles.isNotEmpty()
                try {
                    XiaomiBridge.oplog(
                        "miui-armed",
                        "hooks=${handles.size} factories=${factoryHooks.get()} " +
                            "cardInfo=${cardInfoHooks.get()} missed=${missedTargets.size}",
                    )
                } catch (_: Throwable) {
                }
            }
        }
        startHeartbeatLoop()
    }

    fun isArmed(): Boolean = armed

    // ================================================================ Glide hooks (generic)

    private fun hookGlide(cl: ClassLoader) {
        var installedHere = 0
        for (className in GLIDE_HOOK_CLASSES) {
            val cls = try {
                Class.forName(className, false, cl)
            } catch (t: Throwable) {
                recordMiss("class:$className", t)
                continue
            }
            // Candidates:
            //  - `cls.methods`: public `load(...)` overloads. In Glide 4 the String/Uri/File variants
            //    are interface default methods inherited from ModelTypes (only reachable via methods,
            //    not declaredMethods).
            //  - `cls.declaredMethods`: the private `loadGeneric(Object)` funnel that every Glide
            //    `load` overload funnels through (covers build variants where the public overloads are
            //    interface defaults we cannot intercept).
            val candidates = LinkedHashMap<String, Method>()
            for (method in cls.methods) {
                if (method.name == "load") addGlideCandidate(candidates, method)
            }
            for (method in cls.declaredMethods) {
                if (method.name == "load" || method.name == "loadGeneric") {
                    addGlideCandidate(candidates, method)
                }
            }
            for ((label, method) in candidates) {
                if (!hookedTargets.add(label)) continue
                val unhook = attemptHook(label) {
                    method.isAccessible = true
                    HookBridge.hookMethod(method, object : MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam?) {
                            try {
                                param ?: return
                                onGlideLoad(param)
                            } catch (_: Throwable) {
                            }
                        }
                    })
                }
                if (unhook != null) {
                    handles.add(unhook)
                    glideHooks.incrementAndGet()
                    installedHere++
                }
            }
        }
        if (installedHere == 0) recordMiss("methods:glide", null)
        XiaomiBridge.oplog("glide-hooks", "methods=$installedHere")
    }

    /**
     * Registers one URL-carrying single-argument Glide method under a declaring-class based label
     * (so inherited interface defaults reachable from both hook classes are only hooked once).
     * Returns the label, or null when the signature is not a URL-carrying candidate.
     */
    private fun addGlideCandidate(out: MutableMap<String, Method>, method: Method): String? {
        if (method.parameterCount != 1) return null
        val paramType = method.parameterTypes[0]
        if (paramType != String::class.java && paramType != Any::class.java &&
            paramType != Uri::class.java && paramType != File::class.java
        ) {
            return null
        }
        val label = "glide:${method.declaringClass.name.substringAfterLast('.')}" +
            "#${method.name}(${paramType.simpleName})"
        out[label] = method
        return label
    }

    /**
     * `Glide.load(arg)` interception: replace the argument when we have a rendered face for this
     * card; otherwise record the URL if it looks like a card face. Never throws into the wallet.
     */
    private fun onGlideLoad(param: MethodHook.MethodHookParam) {
        val args = param.args
        if (args.isEmpty()) return
        val raw = args[0] ?: return
        val rawUrl = when (raw) {
            is String -> raw
            is Uri -> raw.toString()
            is File -> raw.absolutePath
            else -> modelUrl(raw)
        } ?: return
        if (!isHttpUrl(rawUrl)) return
        val replacement = resolveReplacement(rawUrl, "glide") ?: return
        args[0] = when (raw) {
            is Uri -> Uri.parse(replacement)
            is File -> File(replacement.removePrefix("file://"))
            else -> replacement // String and Object models both accept a String model in Glide
        }
        val n = replacedUrls.get()
        if (n <= MAX_LOG_LINES) XiaomiBridge.oplog("replace", "#$n glide url=${mask(rawUrl)}")
    }

    /** Best-effort URL extraction from a custom Glide model object (e.g. a GlideUrl wrapper). */
    private fun modelUrl(model: Any): String? {
        return try {
            if (model is CharSequence) return model.toString()
            val cls = model.javaClass
            val name = cls.name
            val plausible = name.contains("glide", ignoreCase = true) ||
                name.contains("url", ignoreCase = true) ||
                name.contains("image", ignoreCase = true)
            if (!plausible) return null
            for (candidate in listOf("getUrl", "getURL", "getUri", "getFile")) {
                try {
                    val m = cls.getMethod(candidate)
                    if (m.parameterCount != 0) continue
                    val value = m.invoke(model)
                    val url = when (value) {
                        is String -> value
                        is Uri -> value.toString()
                        is File -> value.absolutePath
                        is java.net.URL -> value.toString()
                        else -> null
                    }
                    if (!url.isNullOrBlank()) return url
                } catch (_: Throwable) {
                }
            }
            model.toString().takeIf { isHttpUrl(it) }
        } catch (_: Throwable) {
            null
        }
    }

    // ================================================================ MIUI (obfuscated) targets

    /**
     * Locates the reference module's two `com.miui.tsmclient.util` targets by scanning the wallet's
     * dex class names (DexKit-free equivalent of `MethodLocator.locateMiui0/locateMiuiIsland`):
     *  1. static factory `(String) -> Object|String`   (custom GlideUrl wrapper)
     *  2. methods taking `com.miui.tsmclient.entity.CardInfo` (super-island / card entity funnel)
     */
    private fun hookMiuiTargets(cl: ClassLoader) {
        if (factoryHooks.get() >= MAX_FACTORY_HOOKS && cardInfoHooks.get() >= MAX_CARDINFO_HOOKS) return
        val candidates = dexClassNames(cl, listOf(MIUI_UTIL_PACKAGE), MAX_SCAN_CLASSES)
        if (candidates.isEmpty()) {
            recordMiss("dex:$MIUI_UTIL_PACKAGE", null)
            return
        }
        var factories = 0
        var cardInfoObservers = 0
        for (className in candidates) {
            if (factories >= MAX_FACTORY_HOOKS && cardInfoObservers >= MAX_CARDINFO_HOOKS) break
            val cls = try {
                Class.forName(className, false, cl)
            } catch (_: Throwable) {
                continue
            }
            if (cls.isInterface) continue
            if (java.lang.reflect.Modifier.isAbstract(cls.modifiers)) continue
            for (method in cls.declaredMethods) {
                if (method.isSynthetic || method.name.startsWith("<")) continue
                val params = method.parameterTypes
                if (factories < MAX_FACTORY_HOOKS &&
                    java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                    params.size == 1 && params[0] == String::class.java &&
                    (method.returnType == Any::class.java || method.returnType == String::class.java)
                ) {
                    if (hookFactory(method, cls)) factories++
                }
                if (cardInfoObservers < MAX_CARDINFO_HOOKS &&
                    params.any { it.name == CARD_INFO_CLASS }
                ) {
                    if (hookCardInfoObserver(method, cls)) cardInfoObservers++
                }
            }
        }
        XiaomiBridge.oplog(
            "miui-hooks",
            "classes=${candidates.size} factories=$factories cardInfo=$cardInfoObservers",
        )
        if (factories == 0) recordMiss("methods:customGlideUrlFactory", null)
        if (cardInfoObservers == 0) recordMiss("methods:cardInfoObserver", null)
    }

    /**
     * Hooks the obfuscated `CustomGlideUrl`-style static factory. The reference returns the
     * replacement URL from this method (declared return type `Object` there, so a `String` is
     * accepted); a `String` return is also type-safe. Primary replacement path when the wallet wraps
     * the face URL in its own model before calling Glide.
     */
    /** Hooks installed once per label; the delayed retry must not double-hook a method. */
    private val hookedTargets = ConcurrentHashMap.newKeySet<String>()

    private fun hookFactory(method: Method, owner: Class<*>): Boolean {
        val label = "factory:${owner.name.substringAfterLast('.')}#${method.name}"
        if (!hookedTargets.add(label)) return false
        val unhook = attemptHook(label) {
            method.isAccessible = true
            HookBridge.hookMethod(method, object : MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam?) {
                    try {
                        param ?: return
                        val url = param.args.firstOrNull() as? String ?: return
                        if (!isHttpUrl(url)) return
                        val replacement = resolveReplacement(url, "factory") ?: return
                        param.setResult(replacement)
                        val n = replacedUrls.get()
                        if (n <= MAX_LOG_LINES) {
                            XiaomiBridge.oplog("replace", "#$n factory url=${mask(url)}")
                        }
                    } catch (_: Throwable) {
                    }
                }
            })
        }
        if (unhook == null) return false
        handles.add(unhook)
        factoryHooks.incrementAndGet()
        val first = hitLogged.getOrPut(label) { AtomicBoolean(false) }.compareAndSet(false, true)
        if (first) HLog.i("Xiaomi factory hook: ${owner.name}#${method.name}")
        return true
    }

    /**
     * Observes `CardInfo` instances to enrich the report with real names/types (read-only). If the
     * hooked method also returns a card-face URL (the reference's super-island slot), the returned
     * String is replaced with our face as a bonus (type-safe: declared return type is String).
     */
    private fun hookCardInfoObserver(method: Method, owner: Class<*>): Boolean {
        val label = "cardinfo:${owner.name.substringAfterLast('.')}#${method.name}"
        if (!hookedTargets.add(label)) return false
        val unhook = attemptHook(label) {
            method.isAccessible = true
            HookBridge.hookMethod(method, object : MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam?) {
                    try {
                        param ?: return
                        val card = param.args.firstOrNull {
                            it != null && it.javaClass.name == CARD_INFO_CLASS
                        } ?: return
                        val resultUrl = (param.getResult() as? String)?.takeIf { isHttpUrl(it) }
                        val extracted = extractCardInfo(card, resultUrl) ?: return
                        val faceUrl = extracted.url
                        val replacement = if (faceUrl != null && looksLikeCardFaceLenient(faceUrl)) {
                            val identity = identityFor(faceUrl, extracted.type, extracted.name)
                            recordCard(identity, faceUrl, extracted.name, identity.type)
                            (XiaomiBridge.resolveFaceByUrl(faceUrl)
                                ?: XiaomiBridge.resolveFace(identity))?.fileUrl
                        } else {
                            null
                        }
                        if (replacement != null && method.returnType == String::class.java) {
                            param.setResult(replacement)
                            replacedUrls.incrementAndGet()
                        }
                    } catch (_: Throwable) {
                    }
                }
            })
        }
        if (unhook == null) return false
        handles.add(unhook)
        cardInfoHooks.incrementAndGet()
        val first = hitLogged.getOrPut(label) { AtomicBoolean(false) }.compareAndSet(false, true)
        if (first) HLog.i("Xiaomi CardInfo observer: ${owner.name}#${method.name}")
        return true
    }

    /**
     * Reads id/name/type/face URL off a CardInfo instance (reflection, obfuscation tolerant) and
     * caches the result per instance (adapter binds call the hooked funnel repeatedly).
     */
    private fun extractCardInfo(card: Any, directUrl: String?): ObservedCard? {
        val cached = synchronized(cardInfoSeen) { cardInfoSeen[card] }
        if (cached != null) {
            if (directUrl != null && cached.url == null) {
                val updated = extractedWithUrl(cached, directUrl)
                synchronized(cardInfoSeen) { cardInfoSeen[card] = updated }
                return updated
            }
            return cached
        }
        val id = firstGetter(card, ID_GETTERS)
        val name = firstGetter(card, NAME_GETTERS) ?: ""
        val type = mapCardInfoType(firstGetter(card, TYPE_GETTERS))
        val getterUrl = firstGetter(card, URL_GETTERS)?.takeIf { isHttpUrl(it) }
        val url = getterUrl ?: directUrl
        val observed = ObservedCard(id, name, type, url)
        synchronized(cardInfoSeen) { cardInfoSeen[card] = observed }
        val observedCount = cardInfoObserved.incrementAndGet()
        if (observedCount <= MAX_LOG_LINES) {
            XiaomiBridge.oplog(
                "cardinfo",
                "#$observedCount ${card.javaClass.simpleName} id=${mask(id ?: "")} " +
                    "name=${name.take(24)} type=${type ?: "?"} " +
                    "url=${if (url != null) mask(url) else "none"}",
            )
        }
        if (!id.isNullOrBlank() && name.isNotBlank()) {
            val meta = CardMeta(name, type)
            metaById[id] = meta
            metaById[id.lowercase()] = meta
        }
        return observed
    }

    private fun extractedWithUrl(cached: ObservedCard, url: String): ObservedCard {
        if (!looksLikeCardFaceLenient(url)) return cached
        return cached.copy(url = url)
    }

    // ================================================================ replacement / recording

    /**
     * Core interception for one URL: records card-face-looking URLs and returns the `file://`
     * replacement when the panel has a face for it. Returns null to keep the wallet's original URL.
     *
     * Filtering has two levels:
     *  - `factory` / `cardinfo` sources are the wallet's own card-face funnels (reference slots 0/10)
     *    and use the *lenient* rule (only the reference's small-icon / decorative filters);
     *  - the generic Glide hook uses the *strict* rule (must look like an image), because it also
     *    sees icons, banners and avatars;
     *  - a URL the panel explicitly pushed a face for (or that we already recorded once) is always
     *    eligible, so a face pushed for an extension-less CDN URL still applies.
     */
    private fun resolveReplacement(rawUrl: String, source: String): String? {
        if (!isHttpUrl(rawUrl)) return null
        observedUrls.incrementAndGet()
        val faceByUrl = XiaomiBridge.resolveFaceByUrl(rawUrl)
        val normalized = normalizedUrl(rawUrl)
        val known = faceByUrl != null ||
            urlToIdentity.containsKey(rawUrl) ||
            (normalized != null && urlToIdentity.containsKey(normalized))
        val lenient = source == "factory" || source == "cardinfo"
        if (lenient) {
            if (!looksLikeCardFaceLenient(rawUrl)) {
                skippedNonFace.incrementAndGet()
                return null
            }
        } else if (!known && !looksLikeCardFace(rawUrl)) {
            skippedNonFace.incrementAndGet()
            return null
        }
        val typed = typeForUrl(rawUrl)
        // An existing URL alias (or a CardInfo observation) may already know the real name for
        // this URL; the type is kept from the identity so a late CardInfo type cannot duplicate the
        // card under a second storage key.
        val identity = identityFor(rawUrl, typed.type, typed.name)
        val meta = metaForUrl(rawUrl, identity.id)
        val name = meta?.name ?: typed.name
        recordCard(identity, rawUrl, name, identity.type)
        val face = faceByUrl ?: XiaomiBridge.resolveFace(identity)
        if (face != null) {
            val n = replacedUrls.get()
            if (n <= MAX_LOG_LINES) {
                XiaomiBridge.oplog("replace-hit", "src=$source id=${identity.id} key=${face.fileKey}")
            }
            return face.fileUrl
        }
        val n = observedUrls.get()
        if (n <= MAX_LOG_LINES) {
            XiaomiBridge.oplog("url-recorded", "src=$source type=${identity.type} id=${identity.id}")
        }
        return null
    }

    private fun recordCard(identity: CardIdentity, faceUrl: String, name: String, type: String?) {
        val finalType = type ?: identity.type
        val finalIdentity = identity.copy(
            type = finalType,
            name = name.ifBlank { identity.name },
        )
        if (isPlaceholder(finalIdentity, name)) return
        val key = Wallets.configKey(walletPackage, finalIdentity.fileKey)
        try {
            val previous = detected[key]
            val displayName = name.ifBlank {
                finalIdentity.name.ifBlank { defaultName(finalType) }
            }
            val o = JSONObject()
                .put("type", finalType)
                .put("identity", finalIdentity.id)
                .put("name", displayName)
                .put("subtitle", "")
                .put("lastSeen", System.currentTimeMillis())
                .put("appElementIds", JSONArray())
                .put("wallet", walletPackage)
                .put("walletKind", Wallets.kindOf(walletPackage))
                .put("walletLabel", Wallets.labelOf(walletPackage))
                .put("faceUrl", faceUrl)
            detected[key] = o
            aliasUrl(faceUrl, finalIdentity)
            if (previous == null) {
                val n = recordedCards.incrementAndGet()
                if (n <= MAX_LOG_LINES) {
                    XiaomiBridge.oplog(
                        "card",
                        "#$n type=$finalType id=${finalIdentity.id} name=${displayName.take(24)}",
                    )
                }
            }
            reportIfDue()
        } catch (_: Throwable) {
        }
    }

    /** Finds a CardInfo meta entry by identity id, then by "url contains the id" (bounded map). */
    private fun metaForUrl(url: String, identity: String?): CardMeta? {
        if (identity != null) {
            metaById[identity]?.let { return it }
            // identity is "m_<cardId>" when the URL carried an explicit id parameter
            if (identity.startsWith("m_")) metaById[identity.removePrefix("m_")]?.let { return it }
        }
        val lower = url.lowercase()
        for ((id, meta) in metaById) {
            if (id.length >= 4 && lower.contains(id.lowercase())) return meta
        }
        return null
    }

    private fun isPlaceholder(identity: CardIdentity, name: String): Boolean {
        val id = identity.id.trim()
        if (id.isEmpty() || id == "-1" || id == "0" || id == "null") return true
        val label = name.ifBlank { identity.name }.trim()
        return label.contains("添加")
    }

    private fun aliasUrl(url: String, identity: CardIdentity) {
        urlToIdentity[url] = identity
        normalizedUrl(url)?.let { urlToIdentity[it] = identity }
    }

    // ================================================================ URL identity / type / name

    private data class TypedUrl(val type: String, val name: String)

    /** Type derived from a URL: explicit query hints first, then path hints, default transit. */
    private fun typeForUrl(url: String): TypedUrl {
        val query = queryOf(url)
        for (key in TYPE_QUERY_KEYS) {
            val value = query[key]?.lowercase() ?: continue
            when {
                value == "1" -> return TypedUrl(CardTypes.BANK, "")
                value == "5" || value == "33" -> return TypedUrl(CardTypes.TRANSIT, "")
                value == "6" -> return TypedUrl(CardTypes.ACCESS, "")
                value.contains("door") || value.contains("access") ->
                    return TypedUrl(CardTypes.ACCESS, "")
                value.contains("bank") || value.contains("credit") ->
                    return TypedUrl(CardTypes.BANK, "")
                value.contains("campus") || value.contains("school") ->
                    return TypedUrl(CardTypes.CAMPUS, "")
                value.contains("bus") || value.contains("transit") || value.contains("metro") ->
                    return TypedUrl(CardTypes.TRANSIT, "")
            }
        }
        val path = pathOf(url).lowercase()
        return when {
            path.contains("door") || path.contains("access") -> TypedUrl(CardTypes.ACCESS, "")
            path.contains("bank") || path.contains("credit") -> TypedUrl(CardTypes.BANK, "")
            path.contains("campus") || path.contains("school") -> TypedUrl(CardTypes.CAMPUS, "")
            path.contains("ebike") -> TypedUrl(CardTypes.EBIKE, "")
            else -> TypedUrl(CardTypes.TRANSIT, "")
        }
    }

    /**
     * Identity: stable `cardId`-style query parameter, else a hash of the normalized URL.
     * An identity already aliased for this URL is reused verbatim, so a late `CardInfo` observation
     * cannot re-type an existing card and create a duplicate report entry.
     */
    private fun identityFor(url: String, type: String?, name: String): CardIdentity {
        val normalized = normalizedUrl(url)
        val existing = urlToIdentity[url] ?: (normalized?.let { urlToIdentity[it] })
        if (existing != null) {
            return if (name.isNotBlank() && existing.name.isBlank()) existing.copy(name = name) else existing
        }
        val query = queryOf(url)
        var id = ""
        for (key in ID_QUERY_KEYS) {
            val value = query[key]?.trim() ?: continue
            if (isUsableId(value)) {
                id = value
                break
            }
        }
        val identity = if (id.isNotEmpty()) {
            "m_$id"
        } else {
            "u_" + sha256Hex(normalized ?: url).take(16)
        }
        return CardIdentity(
            type = type ?: CardTypes.TRANSIT,
            id = identity,
            name = name.ifBlank { readableName(url) },
        )
    }

    private fun isUsableId(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed.length > 64) return false
        if (trimmed == "0" || trimmed == "-1" || trimmed == "null") return false
        if (trimmed.all { it.isDigit() }) return (trimmed.toLongOrNull() ?: 0L) > 0L
        return trimmed.any { it.isLetterOrDigit() }
    }

    private fun readableName(url: String): String {
        val base = pathOf(url).trimEnd('/').substringAfterLast('/')
        val decoded = try {
            URLDecoder.decode(base, "UTF-8")
        } catch (_: Throwable) {
            base
        }
        val withoutExt = decoded.substringBefore('.').trim()
        if (withoutExt.isEmpty() || withoutExt.length > 24) return ""
        if (withoutExt.matches(Regex("^[0-9a-fA-F]{16,}$"))) return ""
        if (!withoutExt.any { it.isLetter() }) return ""
        return withoutExt
    }

    private fun defaultName(type: String): String = "小米" + CardTypes.displayName(type)

    /**
     * Reference `UrlMapper.normalizedUrl`: drop the fragment, resize/thumbnail path segments and
     * cache/signature query keys so a re-signed or re-sized URL still maps to the same card.
     */
    private fun normalizedUrl(url: String): String? {
        return try {
            val withoutFragment = url.substringBefore('#')
            val path = withoutFragment.substringBefore('?')
            val query = withoutFragment.substringAfter('?', "")
            var normalizedPath = SIZE_SEGMENT.replace(path, "")
            normalizedPath = UUID_SEGMENT.replace(normalizedPath, "")
            normalizedPath = HASH_SEGMENT.replace(normalizedPath, "")
            normalizedPath = normalizedPath.trimEnd('/')
            val kept = query.split('&')
                .filter { it.isNotBlank() }
                .filterNot { RESIZE_QUERY_KEYS.contains(it.substringBefore('=').lowercase()) }
                .sorted()
            if (kept.isEmpty()) normalizedPath else normalizedPath + "?" + kept.joinToString("&")
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Strict card-face heuristic for the generic Glide hook (reference `UrlMapper` small-icon
     * filters + extension/transform checks). Deliberately conservative: a false negative only means
     * the wallet keeps its own face, while a false positive could replace an icon.
     */
    private fun looksLikeCardFace(url: String): Boolean {
        if (!isHttpUrl(url)) return false
        if (!looksLikeCardFaceLenient(url)) return false
        val lower = url.lowercase()
        val path = pathOf(lower)
        val base = path.trimEnd('/').substringAfterLast('/')
        val hasImageExt = IMAGE_EXTENSIONS.any { base.endsWith(it) }
        val sizeSegments = SIZE_SEGMENT.findAll(path).map { it.value }.toList()
        var hasLargeSizeSegment = false
        for (segment in sizeSegments) {
            val w = segment.removePrefix("/w").substringBefore('h').toIntOrNull() ?: 0
            val h = segment.substringAfter('h').toIntOrNull() ?: 0
            if (w in 1..239) return false // small icon variant
            if (w >= 480 && h >= 300) hasLargeSizeSegment = true
        }
        val hasTransform = lower.contains("x-oss-process") || lower.contains("imageview2") ||
            lower.contains("imagemogr") || lower.contains("image_process") || lower.contains("resize")
        return hasImageExt || hasLargeSizeSegment || hasTransform
    }

    /**
     * Lenient card-face filter used for the wallet's own card-face funnels (reference slots 0/10):
     * only the reference's proven small-icon (w270h480 / door logo / Mibi) and decorative-name
     * filters. Accepts extension-less CDN URLs, which the strict rule would reject.
     */
    private fun looksLikeCardFaceLenient(url: String): Boolean {
        if (!isHttpUrl(url)) return false
        val lower = url.lowercase()
        if (lower.contains("w270h480")) return false
        if (lower.contains("/door-card-img/logo/")) return false
        if (lower.contains("/mibi/")) return false
        val path = pathOf(lower)
        val base = path.trimEnd('/').substringAfterLast('/')
        for (bad in NON_FACE_WORDS) {
            if (base.contains(bad)) return false
        }
        for (segment in SIZE_SEGMENT.findAll(path)) {
            val w = segment.value.removePrefix("/w").substringBefore('h').toIntOrNull() ?: 0
            if (w in 1..239) return false // small icon variant
        }
        return true
    }

    private fun pathOf(url: String): String = try {
        Uri.parse(url).path ?: url
    } catch (_: Throwable) {
        url.substringAfter("://").substringAfter('/', "")
    }

    private fun queryOf(url: String): Map<String, String> {
        val out = HashMap<String, String>()
        try {
            val query = url.substringBefore('#').substringAfter('?', "")
            if (query.isEmpty()) return out
            for (pair in query.split('&')) {
                if (pair.isBlank()) continue
                val key = pair.substringBefore('=').lowercase()
                val value = pair.substringAfter('=', "")
                val decoded = try {
                    URLDecoder.decode(value, "UTF-8")
                } catch (_: Throwable) {
                    value
                }
                if (key.isNotEmpty() && decoded.isNotEmpty() && !out.containsKey(key)) out[key] = decoded
            }
        } catch (_: Throwable) {
        }
        return out
    }

    private fun isHttpUrl(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    private fun mask(value: String): String {
        return try {
            val host = try {
                Uri.parse(value).host ?: ""
            } catch (_: Throwable) {
                ""
            }
            "len=${value.length} host=$host sha=${sha256Hex(value).take(10)}"
        } catch (_: Throwable) {
            "len=${value.length}"
        }
    }

    // ================================================================ CardInfo reflection

    private val ID_GETTERS = listOf(
        "getCardId", "getInstanceId", "getCardNo", "getCardNumber", "getSerialNumber",
        "getAid", "getRecordId", "getUid", "getBizId", "getId",
    )
    private val NAME_GETTERS = listOf(
        "getCardName", "getCustomName", "getNickName", "getDisplayName", "getName",
        "getCardNickName", "getTitle",
    )
    private val TYPE_GETTERS = listOf(
        "getCardType", "getCardCategory", "getBizType", "getType", "getCategory", "getCardKind",
    )
    private val URL_GETTERS = listOf(
        "getCardFaceUrl", "getCardFaceImage", "getCardImg", "getCardImage", "getCardPic",
        "getCardImgUrl", "getCardPicUrl", "getFaceImage", "getFaceUrl", "getImageUrl", "getImgUrl",
    )

    private val TYPE_QUERY_KEYS = listOf("cardtype", "card_type", "type", "biztype", "biz_type")
    private val ID_QUERY_KEYS = listOf(
        "cardid", "card_id", "cardno", "card_no", "instanceid", "instance_id",
        "recordid", "record_id", "bizid", "biz_id", "id",
    )
    private val RESIZE_QUERY_KEYS = setOf(
        "w", "h", "width", "height", "size", "q", "quality", "format", "fmt",
        "thumbnail", "thumb", "resize", "scale", "x-oss-process", "imageview2",
        "ts", "t", "v", "_", "sign", "signature", "expires", "expire",
    )
    private val SIZE_SEGMENT = Regex("/w\\d+h\\d+(?=[/?#]|$)")
    private val HASH_SEGMENT = Regex("(^|/)[0-9a-fA-F]{12,}(?=/)")
    private val UUID_SEGMENT = Regex(
        "(^|/)[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(?=/)",
    )
    private val IMAGE_EXTENSIONS = listOf(".png", ".jpg", ".jpeg", ".webp", ".bmp", ".heic")
    private val NON_FACE_WORDS = listOf(
        "logo", "icon", "banner", "splash", "avatar", "qrcode", "qr_", "_qr", "guide",
        "advert", "promotion", "share", "loading", "placeholder", "small_",
    )

    /** Reflection caches for CardInfo getters: class#getter -> found method / known-absent. */
    private val getterCache = ConcurrentHashMap<String, Method>()
    private val getterAbsent = ConcurrentHashMap.newKeySet<String>()

    /** Per-CardInfo-instance extraction cache (adapter binds hit the same object repeatedly). */
    private val cardInfoSeen = Collections.synchronizedMap(WeakHashMap<Any, ObservedCard>())

    private fun firstGetter(target: Any, names: List<String>): String? {
        val cls = target.javaClass
        for (name in names) {
            val key = cls.name + "#" + name
            if (getterAbsent.contains(key)) continue
            var method = getterCache[key]
            if (method == null) {
                method = findZeroArgGetter(cls, name)
                if (method == null) {
                    getterAbsent.add(key)
                    continue
                }
                getterCache[key] = method
            }
            val value = invokeGetter(target, method) ?: continue
            if (value.isNotBlank()) return value
        }
        return null
    }

    private fun findZeroArgGetter(cls: Class<*>, name: String): Method? {
        var c: Class<*>? = cls
        var depth = 0
        while (c != null && depth < 8) {
            val method = c.declaredMethods.firstOrNull {
                it.name == name && it.parameterCount == 0 && !it.isSynthetic
            }
            if (method != null) {
                try {
                    method.isAccessible = true
                } catch (_: Throwable) {
                }
                return method
            }
            c = c.superclass
            depth++
        }
        return null
    }

    private fun invokeGetter(target: Any, method: Method): String? {
        return try {
            when (val value = method.invoke(target)) {
                null -> null
                is String -> value.trim().ifEmpty { null }
                is Number -> value.toString()
                is CharSequence -> value.toString().trim().ifEmpty { null }
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** Maps a wallet `CardInfo` type string to one of our logical card types. */
    private fun mapCardInfoType(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val value = raw.lowercase()
        return when {
            value.contains("bank") || value.contains("credit") || value == "1" -> CardTypes.BANK
            value.contains("door") || value.contains("access") || value == "6" -> CardTypes.ACCESS
            value.contains("campus") || value.contains("school") -> CardTypes.CAMPUS
            value.contains("car") && value.contains("key") -> CardTypes.CARKEY
            value.contains("ebike") -> CardTypes.EBIKE
            value.contains("transit") || value.contains("bus") || value.contains("traffic") ||
                value == "5" || value == "33" -> CardTypes.TRANSIT
            else -> null
        }
    }

    // ================================================================ Dex scan (DexKit-free)

    private const val MAX_SCAN_CLASSES = 1200
    private const val MAX_FACTORY_HOOKS = 12
    private const val MAX_CARDINFO_HOOKS = 12

    /**
     * Enumerates class names of the wallet's own dex files whose name starts with one of [prefixes].
     * DexKit-free replacement for the reference `findClass { searchPackages(...) }` query.
     */
    private fun dexClassNames(cl: ClassLoader, prefixes: List<String>, limit: Int): List<String> {
        val out = ArrayList<String>()
        try {
            var loader: ClassLoader? = cl
            var depth = 0
            while (loader != null && depth < 4) {
                val pathList = fieldOf(loader.javaClass, "pathList")?.get(loader)
                val elements = if (pathList != null) {
                    fieldOf(pathList.javaClass, "dexElements")?.get(pathList) as? Array<*>
                } else {
                    null
                }
                if (elements != null) {
                    for (element in elements) {
                        element ?: continue
                        val dexFile = fieldOf(element.javaClass, "dexFile")?.get(element) ?: continue
                        val entries = try {
                            dexFile.javaClass.getMethod("entries").invoke(dexFile)
                                as? java.util.Enumeration<*>
                        } catch (_: Throwable) {
                            null
                        }
                        while (entries != null && entries.hasMoreElements()) {
                            val name = entries.nextElement() as? String ?: continue
                            if (!name.startsWith(MIUI_PACKAGE_PREFIX)) continue
                            if (prefixes.any { name.startsWith(it) }) {
                                out.add(name)
                                if (out.size >= limit) return out
                            }
                        }
                    }
                    break
                }
                loader = loader.parent
                depth++
            }
        } catch (_: Throwable) {
        }
        return out
    }

    private fun fieldOf(clazz: Class<*>, name: String): java.lang.reflect.Field? {
        var c: Class<*>? = clazz
        var depth = 0
        while (c != null && depth < 8) {
            try {
                return c.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: Throwable) {
            }
            c = c.superclass
            depth++
        }
        return null
    }

    // ================================================================ reporting / diagnostics

    private fun reportIfDue() {
        val now = System.currentTimeMillis()
        val last = lastReportAt.get()
        if (now - last < 10_000L) return
        if (!lastReportAt.compareAndSet(last, now)) return
        if (detected.isEmpty()) return
        try {
            XiaomiBridge.sendCards(cardsJson())
            lastHookReportAt = System.currentTimeMillis()
            sendHeartbeat("hook-report")
        } catch (_: Throwable) {
        }
    }

    fun forceReportNow() {
        try {
            lastReportAt.set(0L)
            XiaomiBridge.sendCards(cardsJson())
            lastHookReportAt = System.currentTimeMillis()
        } catch (_: Throwable) {
        }
    }

    fun cardsJson(): String {
        return try {
            val arr = JSONArray()
            for (o in detected.values) arr.put(o)
            arr.toString()
        } catch (_: Throwable) {
            "[]"
        }
    }

    private fun onConfigChanged() {
        // Faces were (re)pushed: nothing is cached in this engine except url->identity aliases, which
        // stay valid. The wallet re-runs its image loads the next time the card page is shown; log
        // the event so diagnostics show the push arrived.
        XiaomiBridge.oplog("config", "cards=${detected.size} urls=${urlToIdentity.size}")
    }

    private fun sendHeartbeat(reason: String) {
        try {
            heartbeatSent = true
            XiaomiBridge.sendHello(reason)
        } catch (_: Throwable) {
        }
    }

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

    fun engineStateJson(): String {
        return try {
            JSONObject()
                .put("walletPackage", walletPackage)
                .put("walletKind", Wallets.KIND_XIAOMI)
                .put("walletLabel", Wallets.labelOf(walletPackage))
                .put("installed", installed)
                .put("armed", armed)
                .put("installedCount", installedHooks.get())
                .put("attemptedCount", attemptedHooks.get())
                .put("cardCount", detected.size)
                .put("observedUrls", observedUrls.get())
                .put("recordedCards", recordedCards.get())
                .put("replaceCount", replacedUrls.get())
                .put("glideHooks", glideHooks.get())
                .put("factoryHooks", factoryHooks.get())
                .put("cardInfoHooks", cardInfoHooks.get())
                .put("cardInfoObserved", cardInfoObserved.get())
                .put("skippedNonFace", skippedNonFace.get())
                .put("urlCount", urlToIdentity.size)
                .put("bridgeConnected", XiaomiBridge.isConnected())
                .put("bridgePort", XiaomiBridge.port())
                .put("pendingCards", XiaomiBridge.pendingCardCount())
                .put("pendingOplogs", XiaomiBridge.pendingOplogCount())
                .put("lastHookReportAt", lastHookReportAt)
                .put("processName", processName)
                .put("isMainProcess", isMainProcess)
                .put("classLoaderName", classLoaderName)
                .put("heartbeatSent", heartbeatSent)
                .put("missedCount", missedTargets.size)
                .put("engineVersion", "xiaomi-r1")
                .put("moduleVersion", MODULE_VERSION)
                .put("missed", JSONArray(missedTargets.toList()))
                .toString()
        } catch (_: Throwable) {
            "{}"
        }
    }

    // ================================================================ hook bookkeeping

    private fun attemptHook(label: String, block: () -> MethodHook.Unhook?): MethodHook.Unhook? {
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
        HLog.lim("xiaomi_miss", 40) { "Xiaomi target missing: $detail" }
        XiaomiBridge.oplog("hook-miss", detail)
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
