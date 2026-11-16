package com.cardpersonality.honor.hook

import com.cardpersonality.honor.hook.oppo.OppoFaceHookEngine

/**
 * Round-22: shared wallet-hook dispatch used by the API-102 module entry.
 *

 *  - entry `Xposed102Entry` (libxposed API 102, META-INF/xposed metadata)
 *
 * The dispatch is framework-agnostic: hook calls go to the internal API-102 HookBridge.
 * (see `com.cardpersonality.honor.hook.api`).

 */
internal object XposedDispatch {

    fun dispatch(packageName: String?, processName: String?, classLoader: ClassLoader) {
        try {
            val pkg = packageName ?: return
            val proc = processName ?: ""
            val wallet = Wallets.target(pkg)
            if (wallet != null) {
                HLog.i("${HLog.ALIVE} wallet=${wallet.kind} pkg=$pkg proc=$proc")
                ProviderClient.logOp("hook", "init", "wallet=${wallet.kind} pkg=$pkg proc=$proc")
                when (wallet.kind) {
                    Wallets.KIND_HONOR -> FaceHookEngine.install(classLoader)
                    else -> OppoFaceHookEngine.install(classLoader, proc, wallet.pkg)
                }
            } else {
                HLog.lim("loaded_$pkg", 1) { "${HLog.ALIVE} pkg=$pkg proc=$proc" }
            }
        } catch (t: Throwable) {
            try {
                HLog.e("dispatch failed for $packageName", t)
            } catch (_: Throwable) {
            }
        }
    }
}
