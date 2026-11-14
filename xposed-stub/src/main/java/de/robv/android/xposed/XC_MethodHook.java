package de.robv.android.xposed;

import java.lang.reflect.Member;

/**
 * Method hook callback. Stub for compileOnly use (api 93).
 */
public abstract class XC_MethodHook extends XCallback {
    public XC_MethodHook() {
        super();
    }

    public XC_MethodHook(int priority) {
        super(priority);
    }

    @Override
    protected void finalize() throws Throwable {
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    @Override
    public void call(Param param) throws Throwable {
        throw new UnsupportedOperationException("stub");
    }

    public static class MethodHookParam extends XCallback.Param {
        public Member method;
        public Object thisObject;
        public Object[] args;

        private Object result = null;
        private Throwable throwable = null;
        private Object returnEarlyResult = null;

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
            this.throwable = null;
            this.returnEarlyResult = result;
        }

        public Object getResultOrThrowable() throws Throwable {
            if (throwable != null) {
                throw throwable;
            }
            return result;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public boolean hasThrowable() {
            return throwable != null;
        }

        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
            this.result = null;
            this.returnEarlyResult = null;
        }
    }

    public final class Unhook implements IXUnhook<XC_MethodHook> {
        private Member hookMethod;

        @Override
        public XC_MethodHook getCallback() {
            return XC_MethodHook.this;
        }

        @Override
        public void unhook() {
            XposedBridge.unhookMethod(hookMethod, XC_MethodHook.this);
        }
    }
}
