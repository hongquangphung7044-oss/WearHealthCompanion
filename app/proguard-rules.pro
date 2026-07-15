# 保留 Wear OS 关键类
-keep class com.wearhealth.companion.** { *; }

# TensorFlow Lite
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# Kotlin 协程
-keepclassmembernames class kotlinx.** { *; }
