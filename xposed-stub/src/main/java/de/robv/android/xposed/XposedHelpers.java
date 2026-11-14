package de.robv.android.xposed;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Reflection helpers exposed by the legacy Xposed API. Stub for compileOnly use (api 93).
 */
public final class XposedHelpers {
    private XposedHelpers() {
    }

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        throw new UnsupportedOperationException("stub");
    }

    public static Class<?> findClassIfExists(String className, ClassLoader classLoader) {
        throw new UnsupportedOperationException("stub");
    }

    public static Field findField(Class<?> clazz, String fieldName) {
        throw new UnsupportedOperationException("stub");
    }

    public static Field findFieldIfExists(Class<?> clazz, String fieldName) {
        throw new UnsupportedOperationException("stub");
    }

    public static Method findMethodExact(Class<?> clazz, String methodName, Object... parameterTypes) {
        throw new UnsupportedOperationException("stub");
    }

    public static Method findMethodExact(String className, ClassLoader classLoader, String methodName,
                                         Object... parameterTypes) {
        throw new UnsupportedOperationException("stub");
    }

    public static Method findMethodExactIfExists(Class<?> clazz, String methodName, Object... parameterTypes) {
        throw new UnsupportedOperationException("stub");
    }

    public static Constructor<?> findConstructorExact(Class<?> clazz, Object... parameterTypes) {
        throw new UnsupportedOperationException("stub");
    }

    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> clazz, String methodName,
                                                         Object... parameterTypesAndCallback) {
        throw new UnsupportedOperationException("stub");
    }

    public static XC_MethodHook.Unhook findAndHookMethod(String className, ClassLoader classLoader,
                                                         String methodName, Object... parameterTypesAndCallback) {
        throw new UnsupportedOperationException("stub");
    }

    public static XC_MethodHook.Unhook findAndHookMethodIfExists(String className, ClassLoader classLoader,
                                                                 String methodName, Object... parameterTypesAndCallback) {
        throw new UnsupportedOperationException("stub");
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        throw new UnsupportedOperationException("stub");
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        throw new UnsupportedOperationException("stub");
    }

    public static Object newInstance(Class<?> clazz, Object... args) {
        throw new UnsupportedOperationException("stub");
    }

    public static Object getObjectField(Object obj, String fieldName) {
        throw new UnsupportedOperationException("stub");
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
        throw new UnsupportedOperationException("stub");
    }

    public static Object getStaticObjectField(Class<?> clazz, String fieldName) {
        throw new UnsupportedOperationException("stub");
    }

    public static void setStaticObjectField(Class<?> clazz, String fieldName, Object value) {
        throw new UnsupportedOperationException("stub");
    }

    public static boolean getBooleanField(Object obj, String fieldName) {
        throw new UnsupportedOperationException("stub");
    }

    public static void setBooleanField(Object obj, String fieldName, boolean value) {
        throw new UnsupportedOperationException("stub");
    }

    public static int getIntField(Object obj, String fieldName) {
        throw new UnsupportedOperationException("stub");
    }

    public static void setIntField(Object obj, String fieldName, int value) {
        throw new UnsupportedOperationException("stub");
    }

    public static long getLongField(Object obj, String fieldName) {
        throw new UnsupportedOperationException("stub");
    }

    public static void setLongField(Object obj, String fieldName, long value) {
        throw new UnsupportedOperationException("stub");
    }

    public static float getFloatField(Object obj, String fieldName) {
        throw new UnsupportedOperationException("stub");
    }

    public static void setFloatField(Object obj, String fieldName, float value) {
        throw new UnsupportedOperationException("stub");
    }

    public static double getDoubleField(Object obj, String fieldName) {
        throw new UnsupportedOperationException("stub");
    }

    public static void setDoubleField(Object obj, String fieldName, double value) {
        throw new UnsupportedOperationException("stub");
    }

    public static Object getAdditionalInstanceField(Object obj, String key) {
        throw new UnsupportedOperationException("stub");
    }

    public static Object setAdditionalInstanceField(Object obj, String key, Object value) {
        throw new UnsupportedOperationException("stub");
    }

    public static Object removeAdditionalInstanceField(Object obj, String key) {
        throw new UnsupportedOperationException("stub");
    }

    public static Object getAdditionalStaticField(Class<?> clazz, String key) {
        throw new UnsupportedOperationException("stub");
    }

    public static Object setAdditionalStaticField(Class<?> clazz, String key, Object value) {
        throw new UnsupportedOperationException("stub");
    }

    public static Object removeAdditionalStaticField(Class<?> clazz, String key) {
        throw new UnsupportedOperationException("stub");
    }
}
