// Top-level build file
plugins {
    id("com.android.application") version "8.5.2" apply false
    // [FIX 2026-08-17] Kotlin 2.3.0：avif-coder 2.2.0 用 Kotlin 2.3 编译（metadata 不兼容 2.0）
    id("org.jetbrains.kotlin.android") version "2.3.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.0" apply false
}
