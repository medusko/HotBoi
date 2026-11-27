import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { load(it) }
    }
}

val requiredSigningProps = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val configuredStoreFile = keystoreProperties.getProperty("storeFile")?.trim().orEmpty()
val resolvedStoreFile = if (configuredStoreFile.isNotEmpty()) {
    val moduleRelative = file(configuredStoreFile)
    if (moduleRelative.exists()) moduleRelative else rootProject.file(configuredStoreFile)
} else {
    null
}
val hasValidReleaseSigning = keystorePropertiesFile.exists() &&
    requiredSigningProps.all { !keystoreProperties.getProperty(it).isNullOrBlank() } &&
    resolvedStoreFile != null &&
    resolvedStoreFile.exists()

val testAdMobBannerId = "ca-app-pub-3940256099942544/6300978111"
val releaseAdMobBannerId = (
    keystoreProperties.getProperty("admobBannerUnitId")?.trim()
        ?: System.getenv("HOTBOI_ADMOB_BANNER_ID")?.trim()
    ).takeUnless { it.isNullOrBlank() } ?: testAdMobBannerId

android {
    namespace = "com.medusko.hotboi"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.medusko.hotboi"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "1.0.2-RC3-Beta"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("Boolean", "SHOW_ADS", "true")
        buildConfigField("String", "ADMOB_BANNER_UNIT_ID", "\"$testAdMobBannerId\"")
    }

    signingConfigs {
        create("release") {
            if (hasValidReleaseSigning) {
                storeFile = resolvedStoreFile
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            buildConfigField("String", "ADMOB_BANNER_UNIT_ID", "\"$releaseAdMobBannerId\"")
            signingConfig = if (hasValidReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                null
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
            buildConfigField("String", "ADMOB_BANNER_UNIT_ID", "\"$testAdMobBannerId\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/versions/**"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/BCKEY.DSA"
            excludes += "META-INF/BCKEY.SF"
            // Strip unused BouncyCastle Post-Quantum lookup tables (~1.22 MB) & X.509 localization bundles
            excludes += "org/bouncycastle/pqc/**"
            excludes += "org/bouncycastle/x509/*.properties"
            excludes += "org/bouncycastle/LICENSE"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.jsch)
    implementation(libs.gson)
    implementation(libs.play.services.ads)
    implementation(libs.bouncycastle.prov)
    testImplementation(libs.junit)
}

gradle.taskGraph.whenReady {
    val releaseBuildTasks = setOf(
        "assembleRelease",
        "bundleRelease",
        "packageRelease",
        "packageReleaseBundle",
        "signReleaseBundle",
        "validateSigningRelease"
    )
    val runningReleasePackaging = allTasks.any { task ->
        task.project == project && task.name in releaseBuildTasks
    }
    if (runningReleasePackaging && !hasValidReleaseSigning) {
        throw GradleException(
            "Release signing is not configured: missing keystore.properties, required signing properties, " +
                "or keystore file. Refusing to fall back to debug signing."
        )
    }
}
