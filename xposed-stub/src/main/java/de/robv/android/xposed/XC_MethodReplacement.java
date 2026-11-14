package de.robv.android.xposed;

/**
 * Replace a method completely. Stub for compileOnly use (api 93).
 */
public abstract class XC_MethodReplacement extends XC_MethodHook {
    public static final XC_MethodReplacement DO_NOTHING = new XC_MethodReplacement(PRIORITY_HIGHEST * 2) {
        @Override
        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
            return null;
        }
    };

    public XC_MethodReplacement() {
        super();
    }

    public XC_MethodReplacement(int priority) {
        super(priority);
    }

    @Override
    protected final void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected abstract Object replaceHookedMethod(MethodHookParam param) throws Throwable;
}
