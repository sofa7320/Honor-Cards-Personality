// Root build script for 卡面工坊 (Card Personality)
// Toolchain (pinned, matches pre-seeded caches):
//   Gradle 8.13 + JDK 17 + AGP 8.11.2 + Kotlin 2.3.20 + Compose compiler 2.3.20
//   Compose AndroidX 1.10.5 + Miuix 0.9.0 (Miuix 0.9.0 metadata requires kotlin-stdlib 2.3.20
//   and Compose Multiplatform foundation 1.10.3 — do NOT bump to 0.9.4/Kotlin 2.4.x here).
// Xposed: legacy API via compileOnly :xposed-stub (LSPosed 2.2.1 supports it; never packaged).
plugins {
    id("com.android.application") version "8.11.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
