# HotBoi ProGuard Rules (Android R8 Full Mode Minification & Resource Shrinking)

# Javascript Interface for WebView Dashboard
-keepattributes JavascriptInterface,Signature,InnerClasses,EnclosingMethod
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# JSch (SSH Telemetry Engine - reflective cipher/kex/mac/keypair loading)
-keep class com.jcraft.jsch.** { *; }
-keep interface com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn org.newsclub.net.unix.**
-dontwarn com.sun.jna.**

# BouncyCastle SPI Providers strictly required by JSch (Ed25519, Curve25519/X25519, ECDSA, RSA, SHA/HMAC, AES/ChaCha)
# Excludes Post-Quantum (org.bouncycastle.pqc.**) so R8 strips unused PQC classes
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.edec.** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.ec.** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.rsa.** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.dsa.** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.dh.** { *; }
-keep class org.bouncycastle.jcajce.provider.digest.** { *; }
-keep class org.bouncycastle.jcajce.provider.symmetric.AES* { *; }
-keep class org.bouncycastle.jcajce.provider.symmetric.ChaCha* { *; }
-keep class org.bouncycastle.jcajce.provider.keystore.** { *; }
-dontwarn org.bouncycastle.**

# Gson & Telemetry Data Models (R8 full mode safe keeps)
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep class com.medusko.hotboi.TelemetrySample { *; }
-keepclassmembers class com.medusko.hotboi.TelemetrySample { <fields>; <methods>; }
