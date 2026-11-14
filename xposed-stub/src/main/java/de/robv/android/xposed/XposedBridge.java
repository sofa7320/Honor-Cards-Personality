package de.robv.android.xposed;

import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.util.Set;

/**
 * Entry point of the legacy Xposed API. Stub for compileOnly use (api 93).
 *
 * IMPORTANT: LSPosed 2.2.1's injected legacy bridge exposes exactly
 * `hookMethod(java.lang.reflect.Member, XC_MethodHook)`. Do NOT add overloads here
 * (e.g. one taking java.lang.reflect.Method): it would win compile-time overload
 * resolution and emit a descriptor that the runtime bridge does not have
 * (NoSuchMethodError observed on device).
 */
public final class XposedBridge {
    public static final int XPOSED_BRIDGE_VERSION = 93;
    public static final String TAG = "Xposed";

    public static PrintStream log = System.out;

    private XposedBridge() {
    }

    public static void log(String text) {
        throw new UnsupportedOperationException("stub");
    }

    public static void log(Throwable t) {
        throw new UnsupportedOperationException("stub");
    }

    public static <T extends XC_MethodHook> XC_MethodHook.Unhook hookMethod(Member hookMethod, T callback) {
        throw new UnsupportedOperationException("stub");
    }

    public static void unhookMethod(Member hookMethod, XC_MethodHook callback) {
        throw new UnsupportedOperationException("stub");
    }

    public static Object invokeOriginalMethod(Member method, Object thisObject, Object[] args) throws Throwable {
        throw new UnsupportedOperationException("stub");
    }

    public static void deoptimizeMethod(Member method) {
        throw new UnsupportedOperationException("stub");
    }

    public static void deoptimizeConstructor(Constructor<?> constructor) {
        throw new UnsupportedOperationException("stub");
    }

    public static boolean isMethodHooked(Member method) {
        throw new UnsupportedOperationException("stub");
    }

    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> hookClass, String methodName, XC_MethodHook callback) {
        throw new UnsupportedOperationException("stub");
    }

    public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> hookClass, XC_MethodHook callback) {
        throw new UnsupportedOperationException("stub");
    }

    public static void setObjectClass(Object obj, Class<?> clazz) {
        throw new UnsupportedOperationException("stub");
    }

    public static Class<?> getObjectClass(Object obj) {
        throw new UnsupportedOperationException("stub");
    }
}
