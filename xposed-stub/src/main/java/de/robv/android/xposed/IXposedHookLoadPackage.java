package de.robv.android.xposed;

import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Module entry point for per-package hooks. Stub for compileOnly use (api 93).
 */
public interface IXposedHookLoadPackage extends IXposedMod {
    void handleLoadPackage(LoadPackageParam lpparam) throws Throwable;
}
