plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.wearhealth.companion"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.wearhealth.companion"
        minSdk = 33          // Wear OS 3+ (Galaxy Watch 4+)
        targetSdk = 34
        // 版本号：CI 构建时通过环境变量注入（github.run_number，保证递增 → 支持覆盖安装）
        // 本地构建回退到 1
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            // 签名配置从本地 keystore 读取（CI 中由 workflow 解码 secret 后写入）
            val storeFilePath = System.getenv("KEYSTORE_PATH")
            val storePassword = System.getenv("KEYSTORE_PASSWORD")
            val keyAlias = System.getenv("KEY_ALIAS")
            val keyPassword = System.getenv("KEY_PASSWORD")
            if (!storeFilePath.isNullOrEmpty()) {
                signingConfig = signingConfigs.create("release") {
                    storeFile = file(storeFilePath)
                    this.storePassword = storePassword
                    this.keyAlias = keyAlias
                    this.keyPassword = keyPassword
                }
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
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
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    lint {
        // ComponentActivity 使用 registerForActivityResult 时 lint 误报 Fragment 版本问题
        disable += "InvalidFragmentVersionForActivityResult"
        abortOnError = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    // Compose for Wear OS
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.compose.material)

    // 协程
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // TensorFlow Lite 本地推理
    implementation(libs.tflite)
    implementation(libs.tflite.support)

    // Wear OS / Health Services
    implementation(libs.play.services.wearable)
    implementation(libs.health.services.client)

    // 网络：调用 HeartVoice ECG 分析 API
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")

    // Samsung Health Sensor SDK（可选：ECG 原始波形采集）
    // .aar 文件放在 app/libs/ 目录，由 CI 从 GitHub Secret 解码
    // 没有 SDK 时编译基础版（只有心率/HRV），有 SDK 时编译完整 ECG 版
    val samsungSdkAar = file("libs/samsung-health-sensor-api.aar")
    if (samsungSdkAar.exists()) {
        implementation(files(samsungSdkAar))
        println(">>> Samsung Health Sensor SDK 已找到，启用 ECG 功能")
    } else {
        println(">>> 未找到 Samsung Health Sensor SDK，编译基础版（无 ECG）")
    }
}
