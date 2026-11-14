package de.robv.android.xposed;

import android.app.Application;

/**
 * Android process helpers exposed by the legacy Xposed API.
 * Stub for compileOnly use (api 93).
 *
 * Note: the real API returns android.app.ActivityThread, which is @hide and therefore absent
 * from the public android.jar this stub module compiles against. The return type is widened to
 * Object here; module code must not call this method (use the real device API only if needed).
 */
public final class AndroidAppHelper {
    private AndroidAppHelper() {
    }

    public static Application currentApplication() {
        throw new UnsupportedOperationException("stub");
    }

    public static Object currentActivityThread() {
        throw new UnsupportedOperationException("stub");
    }

    public static String currentPackageName() {
        throw new UnsupportedOperationException("stub");
    }

    public static String currentProcessName() {
        throw new UnsupportedOperationException("stub");
    }
}
