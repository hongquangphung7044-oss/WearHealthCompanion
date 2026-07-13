# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /path/to/Android/sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.kts.
#
# For more details, see https://developer.android.com/build/shrink-code.

# Keep Room generated implementations
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-keepclassmembers class * { @androidx.room.* <methods>; }

# Keep Wearable Data Layer model classes for serialization
-keep class com.wearhealth.companion.shared.** { *; }

# Keep Kotlin metadata for reflection used by DataMap / JSON
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
