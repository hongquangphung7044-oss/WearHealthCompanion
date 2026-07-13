plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.wearhealth.companion"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wearhealth.companion"
        minSdk = 33          // Wear OS 3+ (Galaxy Watch 4+)
        targetSdk = 35
        // 版本号：CI 构建时通过环境变量注入（github.run_number，保证递增 → 支持覆盖安装）
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0.0"

        // HeartVoice ECG API Key
        // CI 通过环境变量 HEARTVOICE_API_KEY 注入，本地构建用默认值
        buildConfigField(
            "String",
            "HEARTVOICE_API_KEY",
            "\"${System.getenv("HEARTVOICE_API_KEY") ?: "aiecg_sk_ONGAJEzHVxKoZzOMRZVQ5yztNVMBH5Pi"}\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
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
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    lint {
        disable += "InvalidFragmentVersionForActivityResult"
        abortOnError = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose for Wear OS — Material 3
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.compose.material3)

    // 协程
    implementation(libs.kotlinx.coroutines.android)

    // Wear OS
    implementation(libs.play.services.wearable)

    // 网络：调用 HeartVoice ECG 分析 API
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")

    // Samsung Health Sensor SDK（ECG 原始波形采集）
    // .aar 文件放在 app/libs/ 目录，由 CI 从 GitHub Secret 解码
    val samsungSdkAar = file("libs/samsung-health-sensor-api.aar")
    if (samsungSdkAar.exists()) {
        implementation(files(samsungSdkAar))
        println(">>> Samsung Health Sensor SDK 已找到，启用 ECG 功能")
    } else {
        println(">>> 未找到 Samsung Health Sensor SDK，ECG 功能不可用")
    }
}
