plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.wearhealth.companion.shared"
    compileSdk = 35

    defaultConfig {
        minSdk = 26  // 兼容手机 (minSdk 26) 和手表 (minSdk 33)
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
    // JSON 序列化
    implementation("org.json:json:20240303")
    // Wear OS Data Layer API
    implementation(libs.play.services.wearable)
    // OkHttp: DeepSeek API 客户端（手表+手机共用）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Coroutines: DeepSeekApiClient.analyzeEcg/queryBalance 使用 withContext(Dispatchers.IO)
    implementation(libs.kotlinx.coroutines.android)
    // Unit tests cover binary framing/CRC/ACK and payload round trips without device hardware.
    testImplementation("junit:junit:4.13.2")
}
