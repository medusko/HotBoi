# HotBoi ProGuard Rules (Android R8 Minification & Resource Shrinking)

# Javascript Interface for WebView Dashboard
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# JSch & BouncyCastle (SSH Telemetry Engine)
-keep class com.jcraft.jsch.** { *; }
-keep interface com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Gson & Telemetry Data Models
-keep class com.medusko.hotboi.TelemetrySample { *; }
-keep class com.medusko.hotboi.TelemetryRepository$** { *; }
-keepclassmembers class com.medusko.hotboi.TelemetrySample { <fields>; <methods>; }
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**

# AndroidX WorkManager
-keep class com.medusko.hotboi.ThermalWorker { *; }
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class androidx.work.** { *; }
-dontwarn androidx.work.**
