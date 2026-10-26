plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.kongye.docs"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.kongye.docs"
        minSdk = 26
        targetSdk = 36
        versionCode = 16
        versionName = "2.5"
        testInstrumentationRunner = "com.kongye.docs.RegressionInstrumentation"
        manifestPlaceholders["appLabel"] = "空叶文档"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".audit"
            manifestPlaceholders["appLabel"] = "空叶文档测试"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 本地使用：沿用 debug 签名，生成的 release APK 可直接安装
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // WebDAV 自动备份（MKCOL / PROPFIND 等方法 HttpURLConnection 不支持）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
