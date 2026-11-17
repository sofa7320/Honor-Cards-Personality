package com.cardpersonality.honor.hook

import com.cardpersonality.honor.hook.api.HookBridge
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * Round-17 modern module entry (libxposed API 102, Vector / LSPosed 2.x).
 *
 * Loaded from `META-INF/xposed/java_init.list`. `onModuleLoaded` stores `this` in the de.robv
 * HookBridge so the engines' hook calls are translated
 * into `module.hook(...).intercept(...)`. `onPackageReady` calls the shared [XposedDispatch].
 *
 * API-102 only (round 22): the old legacy entry/`de.robv` shim were removed.

 */
class Xposed102Entry : XposedModule() {

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        try {
            HookBridge.attach(this)
            HookBridge.setProcessName(param.processName)
            HLog.i(
                "XPOSED102_ALIVE process=${param.processName} system=${param.isSystemServer} " +
                    "api=$apiVersion framework=${frameworkName} ${frameworkVersion}",
            )
        } catch (t: Throwable) {
            try {
                HLog.e("xposed102 onModuleLoaded failed", t)
            } catch (_: Throwable) {
            }
        }
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val pkg = param.packageName
            val proc = HookBridge.processName
            HLog.i("XPOSED102_READY pkg=$pkg proc=$proc api=$apiVersion")
            XposedDispatch.dispatch(pkg, proc, param.classLoader)
        } catch (t: Throwable) {
            try {
                HLog.e("xposed102 onPackageReady failed for ${param.packageName}", t)
            } catch (_: Throwable) {
            }
        }
    }
}
