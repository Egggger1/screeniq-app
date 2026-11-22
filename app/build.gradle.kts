plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.screeniq.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.screeniq.app"
        minSdk = 23          // 覆盖 Android 6.0+，荣耀绝大多数机型
        targetSdk = 35
        versionCode = 8
        versionName = "0.2.7-悬浮菜单与看图答题"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.webkit:webkit:1.11.0")           // WebView 能力增强
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    // 离线中文 OCR：standalone 版，模型打进 APK，不依赖谷歌服务（GMS）
    // 荣耀/华为国行无 GMS 也能用
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    // 网络请求：直连大模型 API
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
