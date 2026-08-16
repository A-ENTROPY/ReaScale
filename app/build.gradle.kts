// ReaScale app module
// NCNN Vulkan 集成（复刻参考 app 架构）

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "io.reascale.app"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "io.reascale.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "0.3.1-a21"
        vectorDrawables { useSupportLibrary = true }

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            // 环境限制：NDK 工具链是 x86_64 宿主，无法在 aarch64 执行
            // 跳过 strip debug symbols 步骤
            packaging {
                jniLibs {
                    useLegacyPackaging = true
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    // [FIX 2026-08-11] 启用 CMake 编译 libreascale_ncnn.so（从 cpp 源码 + libncnn.a 静态库）
    // 之前是预编译 .so，C++ 改动无法生效
    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Compose BOM（统一管理版本）
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // AndroidX core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    // Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.1")

    // Coil 3（缩略图）
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")

    // 序列化（profile.json）
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.2")

    // 数据存储（DataStore）
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // M1 业务：协程 + 生命周期
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // 2026-08-17：ONNX Runtime 已移除（统一走 NCNN 原生推理，见 ImageProcessor.defaultEngineProvider）
    // 原 fileTree("libs"){ include("*.aar") } 的 onnxruntime aar 已删除，libs 目录不再被引用
    // M2 图片 I/O：ExifInterface、DocumentFile
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // M7 队列（占位声明，M7 阶段才启用 Worker）
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // M7 持久化（占位声明，M7 启用 Room 时需同时加 kapt 插件 + room-compiler）
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")

    // 单元测试
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    // 仪器测试
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}