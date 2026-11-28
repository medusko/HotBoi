package com.medusko.hotboi

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Medusko-themed Android Home Screen AppWidget displaying live SFP+ Optic Temp,
 * CPU Core 0 Temp, Optical Rx Power (dBm), and ICMP Ping Latency with 1-tap refresh.
 * Honors HotBoi's in-app Theme Mode (Dark Obsidian / Warm Linen / System Default).
 */
class HotBoiWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_WIDGET_REFRESH = "com.medusko.hotboi.ACTION_WIDGET_REFRESH"
        private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun getWidgetIds(context: Context): IntArray {
            val manager = AppWidgetManager.getInstance(context)
            return manager.getAppWidgetIds(ComponentName(context, HotBoiWidgetProvider::class.java))
        }

        fun updateAllWidgets(
            context: Context,
            isRefreshing: Boolean = false,
            statusOverride: String? = null
        ) {
            val appCtx = context.applicationContext
            val ids = getWidgetIds(appCtx)
            if (ids.isEmpty()) return

            widgetScope.launch {
                val views = buildRemoteViews(appCtx, isRefreshing = isRefreshing, statusOverride = statusOverride)
                val manager = AppWidgetManager.getInstance(appCtx)
                for (id in ids) {
                    manager.updateAppWidget(id, views)
                }
            }
        }

        fun refreshNowAsync(context: Context, pendingResult: PendingResult? = null) {
            val appCtx = context.applicationContext
            val ids = getWidgetIds(appCtx)
            if (ids.isEmpty()) {
                pendingResult?.finish()
                return
            }

            widgetScope.launch {
                try {
                    val loadingViews = buildRemoteViews(appCtx, isRefreshing = true)
                    val manager = AppWidgetManager.getInstance(appCtx)
                    for (id in ids) {
                        manager.updateAppWidget(id, loadingViews)
                    }

                    val prefs = appCtx.getSharedPreferences(SshCollector.PREFS_NAME, Context.MODE_PRIVATE)
                    val mode = prefs.getString(SshCollector.KEY_COLLECTION_MODE, SshCollector.MODE_SSH) ?: SshCollector.MODE_SSH
                    var errBadge: String? = null

                    when (mode) {
                        SshCollector.MODE_SSH -> {
                            val res = SshCollector.collectViaSsh(appCtx, usePool = false)
                            if (res.isSuccess) {
                                val sample = res.getOrThrow()
                                TelemetryRepository.addSample(appCtx, sample, SshCollector.MODE_SSH)
                                NotificationHelper.checkThresholdAndAlert(appCtx, sample)
                            } else {
                                errBadge = "ERR"
                            }
                        }
                        SshCollector.MODE_MAC_SERVER,
                        SshCollector.MODE_WIN_SERVER,
                        SshCollector.MODE_LINUX_SERVER,
                        SshCollector.MODE_SERVER -> {
                            val serverUrl = CollectorHttpClient.resolveServerUrlForMode(appCtx, mode)
                            val res = CollectorHttpClient.fetchTelemetrySample(appCtx, serverUrl)
                            if (res.isSuccess) {
                                val (_, sample) = res.getOrThrow()
                                TelemetryRepository.addSample(appCtx, sample, mode)
                                NotificationHelper.checkThresholdAndAlert(appCtx, sample)
                            } else {
                                errBadge = "ERR"
                            }
                        }
                        else -> {
                            TelemetryRepository.initialize(appCtx, SshCollector.MODE_OFFLINE)
                        }
                    }

                    val updatedViews = buildRemoteViews(appCtx, isRefreshing = false, statusOverride = errBadge)
                    for (id in ids) {
                        manager.updateAppWidget(id, updatedViews)
                    }
                } finally {
                    pendingResult?.finish()
                }
            }
        }

        internal fun formatTemperature(tempC: Double?, useFahrenheit: Boolean): String {
            if (tempC == null || tempC <= 0.0) return "--"
            return if (useFahrenheit) {
                val f = (tempC * 9.0 / 5.0) + 32.0
                String.format(Locale.US, "%.1f°F", f)
            } else {
                String.format(Locale.US, "%.1f°C", tempC)
            }
        }

        private fun isWidgetDarkMode(context: Context): Boolean {
            val prefs = context.getSharedPreferences(SshCollector.PREFS_NAME, Context.MODE_PRIVATE)
            return when (prefs.getString(SshCollector.KEY_THEME_MODE, SshCollector.THEME_SYSTEM)) {
                SshCollector.THEME_DARK -> true
                SshCollector.THEME_LIGHT -> false
                else -> {
                    val nightFlags = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                    nightFlags == Configuration.UI_MODE_NIGHT_YES
                }
            }
        }

        private fun buildRemoteViews(
            context: Context,
            isRefreshing: Boolean,
            statusOverride: String? = null
        ): RemoteViews {
            val prefs = context.getSharedPreferences(SshCollector.PREFS_NAME, Context.MODE_PRIVATE)
            val mode = prefs.getString(SshCollector.KEY_COLLECTION_MODE, SshCollector.MODE_SSH) ?: SshCollector.MODE_SSH
            val isFahrenheit = prefs.getString(SshCollector.KEY_TEMP_UNIT, SshCollector.UNIT_CELSIUS) == SshCollector.UNIT_FAHRENHEIT
            val thresholdC = prefs.getFloat(SshCollector.KEY_ALERT_THRESHOLD, SshCollector.DEFAULT_ALERT_THRESHOLD.toFloat()).toDouble()
            val devName = prefs.getString(SshCollector.KEY_DEVICE_NAME, SshCollector.DEFAULT_DEVICE_NAME) ?: SshCollector.DEFAULT_DEVICE_NAME
            val sfpIface = prefs.getString(SshCollector.KEY_SFP_INTERFACE, SshCollector.DEFAULT_SFP_INTERFACE) ?: SshCollector.DEFAULT_SFP_INTERFACE
            val isDark = isWidgetDarkMode(context)

            val sample = TelemetryRepository.getLatestSample(context, mode)
            val rv = RemoteViews(context.packageName, R.layout.widget_hotboi_thermal)

            // Apply Medusko Dark Obsidian (#191C23) or Warm Cream/Linen (#FFFDF9) palette
            val cardBgRes = if (isDark) R.drawable.widget_card_bg_dark else R.drawable.widget_card_bg_light
            val tileBgRes = if (isDark) R.drawable.widget_tile_bg_dark else R.drawable.widget_tile_bg_light
            val bluePillRes = if (isDark) R.drawable.widget_status_pill_dark else R.drawable.widget_status_pill_light

            val blueColor = Color.parseColor(if (isDark) "#60A5FA" else "#2E6BD9")
            val amberColor = Color.parseColor(if (isDark) "#FBBF24" else "#B45309")
            val purpleColor = Color.parseColor(if (isDark) "#C084FC" else "#7E22CE")
            val orangeColor = Color.parseColor(if (isDark) "#FF9100" else "#D97706")
            val alertColor = Color.parseColor(if (isDark) "#FF5252" else "#DC2626")
            val textPrimary = Color.parseColor(if (isDark) "#F1F3F8" else "#1E1A16")
            val textSecondary = Color.parseColor(if (isDark) "#B8C2D4" else "#6B6358")
            val textMuted = Color.parseColor(if (isDark) "#8B95A5" else "#948B7D")

            rv.setInt(R.id.widgetRoot, "setBackgroundResource", cardBgRes)
            rv.setInt(R.id.btnWidgetRefresh, "setBackgroundResource", tileBgRes)
            rv.setInt(R.id.tileWidgetOptic, "setBackgroundResource", tileBgRes)
            rv.setInt(R.id.tileWidgetCpu, "setBackgroundResource", tileBgRes)
            rv.setInt(R.id.tileWidgetRx, "setBackgroundResource", tileBgRes)
            rv.setInt(R.id.tileWidgetPing, "setBackgroundResource", tileBgRes)

            rv.setInt(R.id.ivWidgetLogo, "setColorFilter", blueColor)
            rv.setInt(R.id.ivWidgetRefresh, "setColorFilter", textPrimary)
            rv.setTextColor(R.id.tvWidgetTitle, textPrimary)
            rv.setTextColor(R.id.lblWidgetOptic, textSecondary)
            rv.setTextColor(R.id.lblWidgetCpu, textSecondary)
            rv.setTextColor(R.id.lblWidgetRx, textSecondary)
            rv.setTextColor(R.id.lblWidgetPing, textSecondary)
            rv.setTextColor(R.id.tvWidgetRx, purpleColor)
            rv.setTextColor(R.id.tvWidgetPing, orangeColor)
            rv.setTextColor(R.id.tvWidgetFooterLeft, textMuted)
            rv.setTextColor(R.id.tvWidgetFooterRight, textMuted)

            // 1. Click whole widget card -> Launch MainActivity
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val launchPi = PendingIntent.getActivity(
                context,
                2001,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            rv.setOnClickPendingIntent(R.id.widgetRoot, launchPi)

            // 2. Click 1-Tap Refresh button -> Broadcast ACTION_WIDGET_REFRESH
            val refreshIntent = Intent(context, HotBoiWidgetProvider::class.java).apply {
                action = ACTION_WIDGET_REFRESH
            }
            val refreshPi = PendingIntent.getBroadcast(
                context,
                2002,
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            rv.setOnClickPendingIntent(R.id.btnWidgetRefresh, refreshPi)

            rv.setViewVisibility(R.id.ivWidgetRefresh, if (isRefreshing) View.GONE else View.VISIBLE)
            rv.setViewVisibility(R.id.pbWidgetRefresh, if (isRefreshing) View.VISIBLE else View.GONE)

            if (sample != null) {
                val maxTemp = maxOf(sample.opticTemp, sample.cpu0Temp, sample.cpu1Temp)
                val isOverThreshold = maxTemp >= thresholdC

                rv.setTextViewText(R.id.tvWidgetOptic, formatTemperature(sample.opticTemp, isFahrenheit))
                rv.setTextColor(R.id.tvWidgetOptic, if (sample.opticTemp >= thresholdC) alertColor else blueColor)

                rv.setTextViewText(R.id.tvWidgetCpu, formatTemperature(sample.cpu0Temp, isFahrenheit))
                rv.setTextColor(R.id.tvWidgetCpu, if (sample.cpu0Temp >= thresholdC) alertColor else amberColor)

                val rxText = if (sample.rxPower != 0.0) String.format(Locale.US, "%.1f", sample.rxPower) else "--"
                rv.setTextViewText(R.id.tvWidgetRx, rxText)

                val pingText = if (sample.pingMs > 0.0) String.format(Locale.US, "%.1f ms", sample.pingMs) else "--"
                rv.setTextViewText(R.id.tvWidgetPing, pingText)

                val badgeText = when {
                    isRefreshing -> "SYNC…"
                    statusOverride != null -> statusOverride
                    isOverThreshold -> "HOT!"
                    mode == SshCollector.MODE_OFFLINE -> "CACHED"
                    sample.ploam.isNotBlank() -> sample.ploam
                    else -> "LIVE"
                }
                rv.setTextViewText(R.id.tvWidgetState, badgeText)
                val useRedPill = isOverThreshold || statusOverride == "ERR"
                rv.setInt(
                    R.id.tvWidgetState,
                    "setBackgroundResource",
                    if (useRedPill) R.drawable.widget_alert_pill_bg else bluePillRes
                )
                rv.setTextColor(R.id.tvWidgetState, if (useRedPill) alertColor else blueColor)

                val shortTime = if (sample.timestamp.contains(" ")) {
                    sample.timestamp.split(" ")[1].take(5)
                } else {
                    sample.timestamp.takeLast(5)
                }
                rv.setTextViewText(
                    R.id.tvWidgetFooterRight,
                    if (isRefreshing) "Probing…" else "Updated $shortTime"
                )
            } else {
                rv.setTextViewText(R.id.tvWidgetOptic, "--")
                rv.setTextColor(R.id.tvWidgetOptic, blueColor)
                rv.setTextViewText(R.id.tvWidgetCpu, "--")
                rv.setTextColor(R.id.tvWidgetCpu, amberColor)
                rv.setTextViewText(R.id.tvWidgetRx, "--")
                rv.setTextViewText(R.id.tvWidgetPing, "--")

                val badge = statusOverride ?: if (isRefreshing) "SYNC…" else if (mode == SshCollector.MODE_OFFLINE) "OFFLINE" else "READY"
                rv.setTextViewText(R.id.tvWidgetState, badge)
                val isErr = statusOverride == "ERR"
                rv.setInt(
                    R.id.tvWidgetState,
                    "setBackgroundResource",
                    if (isErr) R.drawable.widget_alert_pill_bg else bluePillRes
                )
                rv.setTextColor(R.id.tvWidgetState, if (isErr) alertColor else blueColor)
                rv.setTextViewText(R.id.tvWidgetFooterRight, if (isRefreshing) "Probing…" else "Tap ↻ to probe")
            }

            val modeTag = when (mode) {
                SshCollector.MODE_MAC_SERVER -> "macOS"
                SshCollector.MODE_WIN_SERVER -> "Windows"
                SshCollector.MODE_LINUX_SERVER -> "Linux"
                SshCollector.MODE_OFFLINE -> "Offline"
                else -> sfpIface
            }
            rv.setTextViewText(R.id.tvWidgetFooterLeft, "$devName • $modeTag")
            return rv
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        refreshNowAsync(context, pending)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        updateAllWidgets(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_WIDGET_REFRESH -> {
                val pending = goAsync()
                refreshNowAsync(context, pending)
            }
            Intent.ACTION_CONFIGURATION_CHANGED -> {
                updateAllWidgets(context)
            }
        }
    }
}
