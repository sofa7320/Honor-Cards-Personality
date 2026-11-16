package com.cardpersonality.honor.hook

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed entry point (legacy Xposed API; listed in assets/xposed_init).
 *
 * Runs in EVERY process the module is loaded into. Wallet-process work is delegated to
 * [FaceHookEngine]; every failure is swallowed and logged so the wallet can never crash
 * because of this module (fail-open).
 */
class XposedEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val pkg = lpparam.packageName ?: "?"
            val proc = lpparam.processName ?: "?"
            // Round-10 multi-wallet dispatch: each known wallet package gets its own engine.
            val target = Wallets.target(pkg)
            if (target != null) {
                HLog.i("$ALIVE_MSG wallet=${target.kind} pkg=$pkg proc=$proc first=${lpparam.isFirstApplication}")
                when (target.pkg) {
                    Wallets.HONOR -> FaceHookEngine.install(lpparam.classLoader)
                    else -> com.cardpersonality.honor.hook.oppo.OppoFaceHookEngine.install(lpparam.classLoader, proc)
                }
            } else {
                HLog.lim("loaded_$pkg", 1) { "$ALIVE_MSG pkg=$pkg proc=$proc" }
            }
        } catch (t: Throwable) {
            try {
                HLog.e("handleLoadPackage failed for ${lpparam.packageName}", t)
            } catch (_: Throwable) {
            }
        }
    }

    private companion object {
        const val ALIVE_MSG = HLog.ALIVE
    }
}

/** Shared helper: install a hook and log/track it, never throwing. */
internal fun hookLogged(
    label: String,
    block: () -> XC_MethodHook.Unhook?,
): XC_MethodHook.Unhook? {
    return try {
        val h = block()
        if (h != null) HLog.i("hooked $label") else HLog.w("hook target not found: $label")
        h
    } catch (t: Throwable) {
        HLog.e("hook failed: $label", t)
        null
    }
}

/** Convenience wrapper for an after-call hook with full fail-open protection. */
internal fun safeAfter(label: String, body: (XC_MethodHook.MethodHookParam) -> Unit): XC_MethodHook =
    object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam?) {
            if (param == null) return
            try {
                body(param)
            } catch (t: Throwable) {
                HLog.e("$label after-hook error", t)
            }
        }
    }

/**
 * Finds a method by predicate inside a wallet class, walking superclasses too
 * (some app adapters inherit bind methods instead of declaring them).
 */
internal fun findWalletMethod(
    cl: ClassLoader,
    className: String,
    predicate: (java.lang.reflect.Method) -> Boolean,
): java.lang.reflect.Method? {
    return try {
        var cls: Class<*>? = Class.forName(className, false, cl)
        var depth = 0
        while (cls != null && depth < 8) {
            cls.declaredMethods.firstOrNull(predicate)?.let { method ->
                method.isAccessible = true
                HLog.i("found method on ${cls!!.name}: ${method.name}(${method.parameterTypes.joinToString(",") { it.name }})")
                return method
            }
            cls = cls.superclass
            depth++
        }
        null
    } catch (t: Throwable) {
        HLog.w("class lookup failed: $className (${t.javaClass.simpleName}: ${t.message})")
        null
    }
}
