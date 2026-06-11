# Keep data classes used in DataStore settings
-keep class io.musicassistant.companion.data.settings.AppSettings { *; }
-keep class io.musicassistant.companion.data.settings.ThemeMode { *; }

# Keep kotlinx.serialization data models
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers @kotlinx.serialization.Serializable class io.musicassistant.companion.data.model.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class io.musicassistant.companion.data.model.**$$serializer { *; }

# Keep Media3 classes
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Strip verbose/debug logging from release builds. Log.d/Log.v fire in per-message and per-second
# hot paths (Sendspin message handling, 1Hz clock sync, command sends). Removing them from the
# shipped APK eliminates constant string interpolation + JNI logging churn that kept the CPU busy
# while connected. (minifyEnabled=true makes -assumenosideeffects effective.)
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
