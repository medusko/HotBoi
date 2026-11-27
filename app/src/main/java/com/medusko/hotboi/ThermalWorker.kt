package com.medusko.hotboi

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

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
            // One-shot session for background worker so zero idle sockets remain open after wake
            val result = SshCollector.collectViaSsh(context, usePool = false)
            if (result.isSuccess) {
                val sample = result.getOrThrow()
                TelemetryRepository.addSample(context, sample, SshCollector.MODE_SSH)
                NotificationHelper.checkThresholdAndAlert(context, sample)
            }
        } else if (
            mode == SshCollector.MODE_MAC_SERVER ||
            mode == SshCollector.MODE_WIN_SERVER ||
            mode == SshCollector.MODE_LINUX_SERVER ||
            mode == SshCollector.MODE_SERVER
        ) {
            val serverUrl = CollectorHttpClient.resolveServerUrlForMode(context, mode)
            val fetchRes = CollectorHttpClient.fetchTelemetrySample(context, serverUrl)
            if (fetchRes.isSuccess) {
                val (_, sample) = fetchRes.getOrThrow()
                TelemetryRepository.addSample(context, sample, mode)
                NotificationHelper.checkThresholdAndAlert(context, sample)
            }
        }
        return Result.success()
    }
}
