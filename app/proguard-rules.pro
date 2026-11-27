-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keep class com.stridecoach.running.core.domain.model.** { *; }
-keep class com.stridecoach.running.core.domain.engine.** { *; }
-keep class com.stridecoach.running.core.data.adapter.** { *; }
-keep class com.stridecoach.running.core.data.db.** { *; }
-keep class com.stridecoach.running.widget.** { *; }
-keep class org.maplibre.** { *; }
-keep class com.mapbox.** { *; }
-keepclassmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class **$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontwarn kotlinx.serialization.**
-dontwarn org.maplibre.**
-dontwarn com.mapbox.**
-dontwarn androidx.health.connect.**
