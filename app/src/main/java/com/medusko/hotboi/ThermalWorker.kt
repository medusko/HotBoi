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
        val bgEnabled = prefs.getBoolean(SshCollector.KEY_BG_ENABLED, true)
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
            val serverUrl = prefs.getString(SshCollector.KEY_SERVER_URL, SshCollector.DEFAULT_SERVER_URL) ?: SshCollector.DEFAULT_SERVER_URL
            try {
                val endpoint = if (serverUrl.endsWith("/api/telemetry")) serverUrl else "${serverUrl.trimEnd('/')}/api/telemetry"
                val url = URL(endpoint)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                if (conn.responseCode == 200) {
                    val json = conn.inputStream.bufferedReader().readText()
                    val sample = Gson().fromJson(json, TelemetrySample::class.java)
                    if (sample != null) {
                        TelemetryRepository.addSample(context, sample, mode)
                        NotificationHelper.checkThresholdAndAlert(context, sample)
                    }
                }
            } catch (_: Exception) {}
        }
        return Result.success()
    }
}
