package com.medusko.hotboi

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

object NotificationHelper {

    const val CHANNEL_ID = "hotboi_thermal_alerts"
    const val CHANNEL_NAME = "HotBoi Thermal Alerts"
    const val NOTIFICATION_ID = 1001

    private var lastAlertTimestamp: Long = 0L
    private const val ALERT_COOLDOWN_MS = 3 * 60 * 1000L // 3 minutes

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High priority push notifications when optical transceiver or CPU temperature exceeds threshold"
                enableLights(true)
                lightColor = Color.RED
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400)
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun checkThresholdAndAlert(context: Context, sample: TelemetrySample) {
        val prefs = context.getSharedPreferences(SshCollector.PREFS_NAME, Context.MODE_PRIVATE)
        val thresholdC = prefs.getFloat(
            SshCollector.KEY_ALERT_THRESHOLD,
            SshCollector.DEFAULT_ALERT_THRESHOLD.toFloat()
        ).toDouble()

        val maxTemp = maxOf(sample.opticTemp, sample.cpu0Temp, sample.cpu1Temp)
        if (maxTemp >= thresholdC) {
            val now = System.currentTimeMillis()
            if (now - lastAlertTimestamp > ALERT_COOLDOWN_MS) {
                lastAlertTimestamp = now
                sendThermalAlert(context, sample, thresholdC, isTest = false)
            }
        }
    }

    fun sendThermalAlert(context: Context, sample: TelemetrySample, thresholdC: Double, isTest: Boolean = false) {
        if (!hasNotificationPermission(context)) {
            return
        }

        createNotificationChannel(context)

        val prefs = context.getSharedPreferences(SshCollector.PREFS_NAME, Context.MODE_PRIVATE)
        val isFahrenheit = prefs.getString(SshCollector.KEY_TEMP_UNIT, SshCollector.UNIT_CELSIUS) == SshCollector.UNIT_FAHRENHEIT

        fun fmt(c: Double): String {
            return if (isFahrenheit) {
                val f = c * 9.0 / 5.0 + 32.0
                String.format(java.util.Locale.US, "%.1f°F (%.1f°C)", f, c)
            } else {
                val f = c * 9.0 / 5.0 + 32.0
                String.format(java.util.Locale.US, "%.1f°C (%.1f°F)", c, f)
            }
        }

        val opticStr = fmt(sample.opticTemp)
        val cpuStr = fmt(sample.cpu0Temp)
        val threshStr = fmt(thresholdC)

        val title = if (isTest) "HotBoi Test Notification" else "HotBoi Thermal Trip Alert"
        val shortContent = if (isTest) {
            "Notification system active! Optic: $opticStr | CPU: $cpuStr"
        } else {
            "High temperature warning! SFP+ Optic: $opticStr exceeds threshold $threshStr"
        }

        val bigText = if (isTest) {
            "Notification verified successfully on your device.\n\n" +
            "• SFP+ Optic: $opticStr\n" +
            "• CPU Core 0: $cpuStr\n" +
            "• Configured Alert Limit: $threshStr\n" +
            "Push alerts will trigger automatically when temperature exceeds this limit."
        } else {
            "CRITICAL: Gateway thermal threshold exceeded!\n\n" +
            "• SFP+ Optic: $opticStr\n" +
            "• CPU Core 0: $cpuStr\n" +
            "• Alert Threshold: $threshStr\n" +
            "Please verify SFP+ cooling and air flow immediately."
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val largeIconBitmap = try {
            BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
        } catch (_: Exception) {
            null
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_hotboi_notification)
            .apply {
                if (largeIconBitmap != null) {
                    setLargeIcon(largeIconBitmap)
                }
            }
            .setContentTitle(title)
            .setContentText(shortContent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setColor(Color.parseColor("#EF4444"))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setDefaults(NotificationCompat.DEFAULT_ALL)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }
}
