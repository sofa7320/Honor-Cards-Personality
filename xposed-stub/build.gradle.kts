// compileOnly stubs for the legacy Xposed API (api 93).
// These classes are NEVER packaged into the APK: they only exist so the module
// sources compile. At runtime LSPosed provides the real classes.
//
// Android framework types (View, SharedPreferences, ApplicationInfo, ...) come from a
// compileOnly android.jar; the stubs are a plain java-library module, not an Android one.
plugins {
    id("java-library")
}

val androidSdk: String = System.getenv("ANDROID_HOME")
    ?: System.getenv("ANDROID_SDK_ROOT")
    ?: "D:\\env\\android-sdk"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    compileOnly(files("$androidSdk/platforms/android-36/android.jar"))
}
