# AWAGAM Android ProGuard Rules

# Strip debug logging from release builds; PRIVACY.md promises no DNS query logs,
# and the resolver logs hostnames at these levels
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int i(...);
    public static int v(...);
}

# Keep OkHttp3 classes (used for blocklist fetching)
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Keep dnsjava classes (reflection-heavy)
-keep class org.xbill.DNS.** { *; }

# dnsjava references desktop/Windows APIs that don’t exist on Android
-dontwarn com.sun.jna.**
-dontwarn java.lang.management.**
-dontwarn javax.naming.**
-dontwarn lombok.**
-dontwarn sun.net.spi.nameservice.**

# Keep Ktor classes
-keep class io.ktor.** { *; }

# Keep kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.awagam.android.**$$serializer { *; }
-keepclassmembers class com.awagam.android.** {
    *** Companion;
}
-keepclasseswithmembers class com.awagam.android.** {
    kotlinx.serialization.KSerializer serializer(...);
}
