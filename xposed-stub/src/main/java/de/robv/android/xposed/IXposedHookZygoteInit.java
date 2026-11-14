package de.robv.android.xposed;

/**
 * Module entry point for zygote-time hooks. Stub for compileOnly use (api 93).
 */
public interface IXposedHookZygoteInit extends IXposedMod {
    void initZygote(StartupParam startupParam) throws Throwable;

    class StartupParam {
        public String modulePath;
        public boolean startsSystemServer;
    }
}
