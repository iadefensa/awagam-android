# AWAGAM Android ProGuard Rules

# Keep dnsjava classes (reflection-heavy)
-keep class org.xbill.DNS.** { *; }

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
