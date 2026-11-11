package com.medusko.hotboi

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Logger
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import java.util.concurrent.TimeUnit

class HotBoiApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Register BouncyCastle for ed25519 & curve25519 SSH key support on Android
        try {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        } catch (e: Exception) {
            Log.e("HotBoiApp", "Failed to register BouncyCastleProvider", e)
        }

        // Route JSch logging to Logcat in debug builds only
        if (BuildConfig.DEBUG) {
            JSch.setLogger(object : Logger {
                override fun isEnabled(level: Int): Boolean = true
                override fun log(level: Int, message: String?) {
                    Log.d("JSch", "[$level] $message")
                }
            })
        }

        // Initialize Google Mobile Ads SDK when advertising is enabled
        if (BuildConfig.SHOW_ADS) {
            try {
                com.google.android.gms.ads.MobileAds.initialize(this) {}
            } catch (e: Exception) {
                Log.e("HotBoiApp", "Failed to initialize MobileAds", e)
            }
        }

        // Apply saved theme mode
        val prefs = getSharedPreferences(SshCollector.PREFS_NAME, Context.MODE_PRIVATE)
        val themeMode = prefs.getString(SshCollector.KEY_THEME_MODE, SshCollector.THEME_SYSTEM) ?: SshCollector.THEME_SYSTEM
        when (themeMode) {
            SshCollector.THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            SshCollector.THEME_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }

        // Initialize high priority notification channel
        NotificationHelper.createNotificationChannel(this)

        // Warm telemetry history cache asynchronously off the main thread
        Thread {
            TelemetryRepository.initialize(applicationContext)
        }.start()
        rescheduleBackgroundWork(this)
    }

    companion object {
        fun rescheduleBackgroundWork(context: Context) {
            val prefs = context.getSharedPreferences(SshCollector.PREFS_NAME, Context.MODE_PRIVATE)
            val isEnabled = prefs.getBoolean(SshCollector.KEY_BG_ENABLED, false)
            val wm = WorkManager.getInstance(context)

            if (!isEnabled) {
                wm.cancelUniqueWork("hotboi_thermal_monitor")
                return
            }

            val intervalMin = prefs.getInt(SshCollector.KEY_BG_INTERVAL_MIN, 60).toLong()

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<ThermalWorker>(
                intervalMin, TimeUnit.MINUTES,
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            wm.enqueueUniquePeriodicWork(
                "hotboi_thermal_monitor",
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )
        }
    }
}
