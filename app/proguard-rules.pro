# HotBoi ProGuard Rules (Android R8 Minification & Resource Shrinking)

# Javascript Interface for WebView Dashboard
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# JSch (SSH Telemetry Engine)
-keep class com.jcraft.jsch.** { *; }
-keep interface com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# BouncyCastle SPI Providers needed by JSch for Ed25519 / Curve25519
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-keep class org.bouncycastle.crypto.engines.** { *; }
-keep class org.bouncycastle.crypto.params.** { *; }
-dontwarn org.bouncycastle.**

# Telemetry Data Models
-keep class com.medusko.hotboi.TelemetrySample { *; }
-keep class com.medusko.hotboi.TelemetryRepository$** { *; }
-keepclassmembers class com.medusko.hotboi.TelemetrySample { <fields>; <methods>; }
