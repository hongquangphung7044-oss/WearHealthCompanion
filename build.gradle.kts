// 顶层构建文件
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.compose.compiler) apply false
    kotlin("kapt") version "2.0.0" apply false
}
