package com.medusko.hotboi

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import java.net.HttpURLConnection
import java.net.URL

class ThermalWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val prefs = context.getSharedPreferences(SshCollector.PREFS_NAME, Context.MODE_PRIVATE)
        val bgEnabled = prefs.getBoolean(SshCollector.KEY_BG_ENABLED, false)
        if (!bgEnabled) {
            return Result.success()
        }

        val mode = prefs.getString(SshCollector.KEY_COLLECTION_MODE, SshCollector.MODE_SSH) ?: SshCollector.MODE_SSH
        if (mode == SshCollector.MODE_SSH) {
            val result = SshCollector.collectViaSsh(context)
            if (result.isSuccess) {
                val sample = result.getOrThrow()
                TelemetryRepository.addSample(context, sample, SshCollector.MODE_SSH)
                NotificationHelper.checkThresholdAndAlert(context, sample)
                return Result.success()
            }
        } else if (mode == SshCollector.MODE_MAC_SERVER || mode == SshCollector.MODE_WIN_SERVER || mode == SshCollector.MODE_LINUX_SERVER || mode == SshCollector.MODE_SERVER) {
            val prefKey = when (mode) {
                SshCollector.MODE_WIN_SERVER -> SshCollector.KEY_WIN_SERVER_URL
                SshCollector.MODE_LINUX_SERVER -> SshCollector.KEY_LINUX_SERVER_URL
                SshCollector.MODE_MAC_SERVER -> SshCollector.KEY_MAC_SERVER_URL
                else -> SshCollector.KEY_SERVER_URL
            }
            val defaultUrl = when (mode) {
                SshCollector.MODE_WIN_SERVER -> SshCollector.DEFAULT_WIN_SERVER_URL
                SshCollector.MODE_LINUX_SERVER -> SshCollector.DEFAULT_LINUX_SERVER_URL
                SshCollector.MODE_MAC_SERVER -> SshCollector.DEFAULT_MAC_SERVER_URL
                else -> SshCollector.DEFAULT_SERVER_URL
            }
            val serverUrl = prefs.getString(prefKey, defaultUrl)?.trim() ?: defaultUrl
            var conn: HttpURLConnection? = null
            try {
                val base = serverUrl.trimEnd('/')
                val endpoint = if (base.endsWith("/api/telemetry")) base else "$base/api/telemetry"
                val url = URL(endpoint)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 4000
                }
                if (conn.responseCode == 200) {
                    val json = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val sample = Gson().fromJson(json, TelemetrySample::class.java)
                    if (sample != null) {
                        TelemetryRepository.addSample(context, sample, mode)
                        NotificationHelper.checkThresholdAndAlert(context, sample)
                    }
                }
            } catch (_: Exception) {
            } finally {
                conn?.disconnect()
            }
        }
        return Result.success()
    }
}
