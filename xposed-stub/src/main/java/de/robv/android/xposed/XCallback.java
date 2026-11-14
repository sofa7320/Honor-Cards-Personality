package de.robv.android.xposed;

/**
 * Base class of all hook callbacks. Stub for compileOnly use (api 93).
 */
public abstract class XCallback implements Comparable<XCallback> {
    public static final int PRIORITY_DEFAULT = 50;
    public static final int PRIORITY_LOWEST = -10000;
    public static final int PRIORITY_HIGHEST = 10000;

    public final int priority;

    public XCallback() {
        this.priority = PRIORITY_DEFAULT;
    }

    public XCallback(int priority) {
        this.priority = priority;
    }

    public static void callAll(Param param) {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public int compareTo(XCallback other) {
        return 0;
    }

    public abstract void call(Param param) throws Throwable;

    public static abstract class Param {
    }

    public interface IXUnhook<T> {
        T getCallback();

        void unhook();
    }
}
