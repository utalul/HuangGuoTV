plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.huangguo.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.huangguo.tv"
        minSdk = 21
        targetSdk = 33
        versionCode = 100
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 直接指定 debug 簽名，確保 CI/CD 與無自訂金鑰環境均能穩定產生可安裝 APK
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    compileOptions {
        // 開啟 Core Library Desugaring，防止 Java 8+ API 在 Android 5.0~7.0 舊電視盒發生 NoClassDefFoundError
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // Java 8+ API 脫糖支援庫 (API 21~24 舊系統必備)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    // 極致輕量原生依賴
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.annotation:annotation:1.7.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // AndroidX Media3 (ExoPlayer) 支援原生滿版播放與 HLS (.m3u8)
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.2.1")
    implementation("androidx.media3:media3-ui:1.2.1")

    // 圖片載入庫 (Coil 輕量非同步圖片載入與快取)
    implementation("io.coil-kt:coil:2.5.0")

    // 原生 Android TV 列表與卡片 UI
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")
}
