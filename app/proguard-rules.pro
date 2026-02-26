# Keep data classes used in DataStore settings
-keep class io.musicassistant.companion.data.settings.AppSettings { *; }
-keep class io.musicassistant.companion.data.settings.ThemeMode { *; }

# Keep JavaScript interface class and methods (called from WebView JS)
-keep class io.musicassistant.companion.ui.webview.WebViewHolder$MaAndroidBridge { *; }

# Keep Media3 classes
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
