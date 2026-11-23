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
        versionCode = 13
        versionName = "0.5.1-安全加固与误点防护"

        // 只打真机在用的两种 ABI。ML Kit 的识别引擎每个 ABI 要 ~11MB，
        // 四个 ABI 全带就是 45MB 白装；x86/x86_64 是模拟器用的，手机根本用不到。
        // 保留 armeabi-v7a 是为了老机型也能装——宁可多 7MB，也不能装不上。
        ndk {
            abiFilters.add("arm64-v8a")
            abiFilters.add("armeabi-v7a")
        }
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

    // 离线中文识别：standalone 版，模型打进 APK，不依赖 GMS。
    // 它给的不只是文字，还有每一行的坐标框——自动点选能点中"画上去的选项"就靠这个。
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    // 网络请求：直连大模型 API
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
