package com.medusko.hotboi

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.InputStreamReader

/**
 * High-performance persistence repository providing source-isolated telemetry caching.
 * Each collection mode (Direct SSH, macOS Server, Windows Server, Linux Server, Offline)
 * maintains its own dedicated historical store, preventing sample cross-contamination or loss.
 */
object TelemetryRepository {

    private const val MAX_SAMPLES = 2500
    private val gson = Gson()
    private val modeCaches = mutableMapOf<String, MutableList<TelemetrySample>>()

    private fun resolveMode(context: Context, mode: String?): String {
        if (!mode.isNullOrBlank()) return mode
        val prefs = context.getSharedPreferences(SshCollector.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(SshCollector.KEY_COLLECTION_MODE, SshCollector.MODE_SSH) ?: SshCollector.MODE_SSH
    }

    private fun getFileName(mode: String): String {
        return when (mode) {
            SshCollector.MODE_MAC_SERVER -> "macos_server_history.json"
            SshCollector.MODE_WIN_SERVER -> "windows_server_history.json"
            SshCollector.MODE_LINUX_SERVER -> "linux_server_history.json"
            SshCollector.MODE_OFFLINE -> "offline_history.json"
            else -> "direct_ssh_history.json"
        }
    }

    @Synchronized
    fun initialize(context: Context, explicitMode: String? = null) {
        val mode = resolveMode(context, explicitMode)
        if (modeCaches.containsKey(mode)) return

        val fileName = getFileName(mode)
        val file = File(context.filesDir, fileName)
        val loadedList = mutableListOf<TelemetrySample>()

        if (file.exists()) {
            try {
                val json = file.readText()
                val type = object : TypeToken<List<TelemetrySample>>() {}.type
                val list: List<TelemetrySample> = gson.fromJson(json, type) ?: emptyList()
                loadedList.addAll(list)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Migrate existing unified history to Direct SSH if direct_ssh_history.json does not exist yet
        if (loadedList.isEmpty() && mode == SshCollector.MODE_SSH) {
            val legacyFile = File(context.filesDir, "telemetry_history.json")
            if (legacyFile.exists()) {
                try {
                    val json = legacyFile.readText()
                    val type = object : TypeToken<List<TelemetrySample>>() {}.type
                    val list: List<TelemetrySample> = gson.fromJson(json, type) ?: emptyList()
                    if (list.isNotEmpty()) {
                        loadedList.addAll(list)
                    }
                } catch (_: Exception) {}
            }
        }

        // Only seed Direct SSH from asset CSV if completely empty
        if (loadedList.isEmpty() && mode == SshCollector.MODE_SSH) {
            seedFromAssets(context, loadedList)
            save(context, mode, loadedList)
        }

        modeCaches[mode] = loadedList
    }

    private fun seedFromAssets(context: Context, targetList: MutableList<TelemetrySample>) {
        try {
            context.assets.open("initial_telemetry.csv").use { inputStream ->
                InputStreamReader(inputStream).useLines { lines ->
                    val iterator = lines.iterator()
                    if (iterator.hasNext()) iterator.next() // skip header
                    for (line in iterator) {
                        val parts = line.split(",")
                        if (parts.size >= 9) {
                            val sample = TelemetrySample(
                                timestamp = parts[0].trim(),
                                opticTemp = parts[1].trim().toDoubleOrNull() ?: 0.0,
                                cpu0Temp = parts[2].trim().toDoubleOrNull() ?: 0.0,
                                cpu1Temp = parts[3].trim().toDoubleOrNull() ?: 0.0,
                                ploam = parts[4].trim(),
                                rxPower = parts[5].trim().toDoubleOrNull() ?: 0.0,
                                txPower = parts[6].trim().toDoubleOrNull() ?: 0.0,
                                biasMa = parts[7].trim().toDoubleOrNull() ?: 0.0,
                                pingMs = parts[8].trim().toDoubleOrNull() ?: 0.0
                            )
                            if (sample.cpu0Temp > 0.0) {
                                targetList.add(sample)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun addSample(context: Context, sample: TelemetrySample, explicitMode: String? = null) {
        val mode = resolveMode(context, explicitMode)
        initialize(context, mode)
        val list = modeCaches[mode] ?: mutableListOf()
        list.add(sample)
        if (list.size > MAX_SAMPLES) {
            val trimCount = list.size - MAX_SAMPLES
            for (i in 0 until trimCount) {
                list.removeAt(0)
            }
        }
        modeCaches[mode] = list
        save(context, mode, list)
    }

    @Synchronized
    fun setSamples(context: Context, newSamples: List<TelemetrySample>, explicitMode: String? = null) {
        val mode = resolveMode(context, explicitMode)
        initialize(context, mode)
        if (newSamples.isNotEmpty()) {
            val list = newSamples.takeLast(MAX_SAMPLES).toMutableList()
            modeCaches[mode] = list
            save(context, mode, list)
        }
    }

    @Synchronized
    fun getAllSamples(context: Context, explicitMode: String? = null): List<TelemetrySample> {
        val mode = resolveMode(context, explicitMode)
        initialize(context, mode)
        val list = modeCaches[mode] ?: emptyList()
        // If server mode cache is initially empty before network reply, provide fallback
        if (list.isEmpty() && mode != SshCollector.MODE_SSH) {
            initialize(context, SshCollector.MODE_SSH)
            return ArrayList(modeCaches[SshCollector.MODE_SSH] ?: emptyList())
        }
        return ArrayList(list)
    }

    @Synchronized
    fun getLatestSample(context: Context, explicitMode: String? = null): TelemetrySample? {
        val list = getAllSamples(context, explicitMode)
        return list.lastOrNull()
    }

    @Synchronized
    private fun save(context: Context, mode: String, list: List<TelemetrySample>) {
        try {
            val fileName = getFileName(mode)
            val file = File(context.filesDir, fileName)
            val json = gson.toJson(list)
            file.writeText(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun toJsonBundle(context: Context, explicitMode: String? = null): String {
        val mode = resolveMode(context, explicitMode)
        val samples = getAllSamples(context, mode)
        val latest = samples.lastOrNull()
        val labels = mutableListOf<String>()
        val fullTimestamps = mutableListOf<String>()
        val opticData = mutableListOf<Double>()
        val cpu0Data = mutableListOf<Double>()
        val cpu1Data = mutableListOf<Double>()
        val pingData = mutableListOf<Double>()

        for (s in samples) {
            fullTimestamps.add(s.timestamp)
            val timeLabel = if (s.timestamp.contains(" ")) {
                val t = s.timestamp.split(" ")[1]
                t.substring(0, minOf(5, t.length))
            } else {
                s.timestamp
            }
            labels.add(timeLabel)
            opticData.add(s.opticTemp)
            cpu0Data.add(s.cpu0Temp)
            cpu1Data.add(s.cpu1Temp)
            pingData.add(s.pingMs)
        }

        val prefs = context.getSharedPreferences(SshCollector.PREFS_NAME, Context.MODE_PRIVATE)
        val pingTargetLabel = prefs.getString(SshCollector.KEY_PING_TARGET_LABEL, "8.8.8.8 (Google)") ?: "8.8.8.8 (Google)"
        val deviceName = prefs.getString(SshCollector.KEY_DEVICE_NAME, SshCollector.DEFAULT_DEVICE_NAME) ?: SshCollector.DEFAULT_DEVICE_NAME
        val portName = prefs.getString(SshCollector.KEY_SFP_PORT_LABEL, SshCollector.DEFAULT_SFP_PORT_LABEL) ?: SshCollector.DEFAULT_SFP_PORT_LABEL

        val sourceLabel = when (mode) {
            SshCollector.MODE_WIN_SERVER -> "Windows Server (Desky • BETA)"
            SshCollector.MODE_MAC_SERVER -> "macOS Server (BETA)"
            SshCollector.MODE_LINUX_SERVER -> "Linux Server (BETA)"
            SshCollector.MODE_SERVER -> "Remote Server (BETA)"
            SshCollector.MODE_OFFLINE -> "Offline (Cached)"
            else -> "Direct SSH"
        }

        val tempUnit = prefs.getString(SshCollector.KEY_TEMP_UNIT, SshCollector.UNIT_CELSIUS) ?: SshCollector.UNIT_CELSIUS
        val thresholdC = prefs.getFloat(SshCollector.KEY_ALERT_THRESHOLD, SshCollector.DEFAULT_ALERT_THRESHOLD.toFloat()).toDouble()
        val themeMode = prefs.getString(SshCollector.KEY_THEME_MODE, SshCollector.THEME_SYSTEM) ?: SshCollector.THEME_SYSTEM

        val isLight = when (themeMode) {
            SshCollector.THEME_LIGHT -> true
            SshCollector.THEME_DARK -> false
            else -> {
                val nightModeFlags = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
                nightModeFlags != android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
        }

        val bundle = mapOf(
            "latest" to latest,
            "sampleCount" to samples.size,
            "labels" to labels,
            "fullTimestamps" to fullTimestamps,
            "opticData" to opticData,
            "cpu0Data" to cpu0Data,
            "cpu1Data" to cpu1Data,
            "pingData" to pingData,
            "deviceName" to deviceName,
            "portName" to portName,
            "pingTarget" to pingTargetLabel,
            "mode" to mode,
            "sourceLabel" to sourceLabel,
            "tempUnit" to tempUnit,
            "threshold" to thresholdC,
            "isLight" to isLight
        )

        return gson.toJson(bundle)
    }
}
