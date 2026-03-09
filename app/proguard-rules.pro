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
