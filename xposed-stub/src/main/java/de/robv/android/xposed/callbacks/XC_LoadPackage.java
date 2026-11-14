package de.robv.android.xposed.callbacks;

import de.robv.android.xposed.XCallback;
import de.robv.android.xposed.IXposedHookLoadPackage;

/**
 * Callback for package load. Stub for compileOnly use (api 93).
 */
public abstract class XC_LoadPackage extends XCallback implements IXposedHookLoadPackage {
    public XC_LoadPackage() {
        super();
    }

    public XC_LoadPackage(int priority) {
        super(priority);
    }

    public static final class LoadPackageParam extends XCallback.Param {
        public String packageName;
        public String processName;
        public boolean isFirstApplication;
        public ClassLoader classLoader;
        public android.content.pm.ApplicationInfo appInfo;
    }
}
