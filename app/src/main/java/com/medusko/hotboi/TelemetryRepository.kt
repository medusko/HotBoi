package com.medusko.hotboi

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.InputStreamReader

/**
 * High-performance persistence repository providing source- and interface-isolated telemetry caching.
 * Each collection mode and SFP+ port interface maintains its own dedicated historical store,
 * preventing sample cross-contamination or stale seeded data in live modes.
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

    private fun resolveCacheKey(context: Context, mode: String): String {
        if (mode == SshCollector.MODE_SSH) {
            val prefs = context.getSharedPreferences(SshCollector.PREFS_NAME, Context.MODE_PRIVATE)
            val rawIface = prefs.getString(SshCollector.KEY_SFP_INTERFACE, SshCollector.DEFAULT_SFP_INTERFACE)
                ?: SshCollector.DEFAULT_SFP_INTERFACE
            val safeIface = rawIface.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { SshCollector.DEFAULT_SFP_INTERFACE }
            return "${SshCollector.MODE_SSH}_$safeIface"
        }
        return mode
    }

    private fun getFileName(cacheKey: String): String {
        return when {
            cacheKey == SshCollector.MODE_MAC_SERVER -> "macos_server_history.json"
            cacheKey == SshCollector.MODE_WIN_SERVER -> "windows_server_history.json"
            cacheKey == SshCollector.MODE_LINUX_SERVER -> "linux_server_history.json"
            cacheKey == SshCollector.MODE_SERVER -> "remote_server_history.json"
            cacheKey == SshCollector.MODE_OFFLINE -> "offline_history.json"
            cacheKey.startsWith("${SshCollector.MODE_SSH}_") -> {
                val iface = cacheKey.removePrefix("${SshCollector.MODE_SSH}_")
                "direct_ssh_${iface}_history.json"
            }
            else -> "direct_ssh_history.json"
        }
    }

    /**
     * Filters out legacy bundled October 2026 CSV samples that were previously seeded into live SSH history.
     */
    private fun isBundledSeedSample(sample: TelemetrySample): Boolean {
        return sample.timestamp.startsWith("2026-10-22") ||
            sample.timestamp.startsWith("2026-10-23") ||
            sample.timestamp.startsWith("2026-10-24") ||
            sample.timestamp.startsWith("2026-10-25") ||
            sample.timestamp.startsWith("2026-10-26")
    }

    @Synchronized
    fun initialize(context: Context, explicitMode: String? = null) {
        val mode = resolveMode(context, explicitMode)
        val cacheKey = resolveCacheKey(context, mode)
        if (modeCaches.containsKey(cacheKey)) return

        val fileName = getFileName(cacheKey)
        val file = File(context.filesDir, fileName)
        val loadedList = mutableListOf<TelemetrySample>()
        var purgedSeedData = false

        if (file.exists()) {
            try {
                val json = file.readText()
                val type = object : TypeToken<List<TelemetrySample>>() {}.type
                val list: List<TelemetrySample> = gson.fromJson(json, type) ?: emptyList()
                if (mode == SshCollector.MODE_OFFLINE) {
                    loadedList.addAll(list)
                } else {
                    val filtered = list.filterNot { isBundledSeedSample(it) }
                    if (filtered.size != list.size) {
                        purgedSeedData = true
                    }
                    loadedList.addAll(filtered)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Migrate existing live samples from legacy direct_ssh_history.json for eth6, excluding seeded CSV rows
        if (loadedList.isEmpty() && cacheKey == "${SshCollector.MODE_SSH}_eth6") {
            val legacySshFile = File(context.filesDir, "direct_ssh_history.json")
            if (legacySshFile.exists()) {
                try {
                    val json = legacySshFile.readText()
                    val type = object : TypeToken<List<TelemetrySample>>() {}.type
                    val list: List<TelemetrySample> = gson.fromJson(json, type) ?: emptyList()
                    val liveOnly = list.filterNot { isBundledSeedSample(it) }
                    if (liveOnly.isNotEmpty()) {
                        loadedList.addAll(liveOnly)
                        purgedSeedData = true
                    }
                } catch (_: Exception) {}
            }
        }

        // Only seed OFFLINE mode from bundled asset CSV (never pollute live Direct SSH or Server modes)
        if (loadedList.isEmpty() && mode == SshCollector.MODE_OFFLINE) {
            seedFromAssets(context, loadedList)
            save(context, cacheKey, loadedList)
        } else if (purgedSeedData) {
            save(context, cacheKey, loadedList)
        }

        modeCaches[cacheKey] = loadedList
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
        val cacheKey = resolveCacheKey(context, mode)
        initialize(context, mode)
        val list = modeCaches[cacheKey] ?: mutableListOf()
        list.add(sample)
        if (list.size > MAX_SAMPLES) {
            val trimCount = list.size - MAX_SAMPLES
            list.subList(0, trimCount).clear()
        }
        modeCaches[cacheKey] = list
        save(context, cacheKey, list)
    }

    @Synchronized
    fun setSamples(context: Context, newSamples: List<TelemetrySample>, explicitMode: String? = null) {
        val mode = resolveMode(context, explicitMode)
        val cacheKey = resolveCacheKey(context, mode)
        initialize(context, mode)
        if (newSamples.isNotEmpty()) {
            val list = newSamples.takeLast(MAX_SAMPLES).toMutableList()
            modeCaches[cacheKey] = list
            save(context, cacheKey, list)
        }
    }

    @Synchronized
    fun getAllSamples(context: Context, explicitMode: String? = null): List<TelemetrySample> {
        val mode = resolveMode(context, explicitMode)
        val cacheKey = resolveCacheKey(context, mode)
        initialize(context, mode)
        val list = modeCaches[cacheKey] ?: emptyList()
        return ArrayList(list)
    }

    @Synchronized
    fun getLatestSample(context: Context, explicitMode: String? = null): TelemetrySample? {
        val list = getAllSamples(context, explicitMode)
        return list.lastOrNull()
    }

    @Synchronized
    private fun save(context: Context, cacheKey: String, list: List<TelemetrySample>) {
        try {
            val fileName = getFileName(cacheKey)
            val file = File(context.filesDir, fileName)
            val tmpFile = File(context.filesDir, "$fileName.tmp")
            val json = gson.toJson(list)
            tmpFile.writeText(json, Charsets.UTF_8)
            if (!tmpFile.renameTo(file)) {
                tmpFile.copyTo(file, overwrite = true)
                tmpFile.delete()
            }
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
        val pingTargetLabel = prefs.getString(SshCollector.KEY_PING_TARGET_LABEL, "1.1.1.1 (Cloudflare)") ?: "1.1.1.1 (Cloudflare)"
        val savedDevName = prefs.getString(SshCollector.KEY_DEVICE_NAME, SshCollector.DEFAULT_DEVICE_NAME) ?: SshCollector.DEFAULT_DEVICE_NAME
        val deviceName = if (savedDevName == "UniFi Cloud Gateway Fiber (White Bean)") {
            SshCollector.DEFAULT_DEVICE_NAME
        } else {
            savedDevName
        }
        val portName = prefs.getString(SshCollector.KEY_SFP_PORT_LABEL, SshCollector.DEFAULT_SFP_PORT_LABEL) ?: SshCollector.DEFAULT_SFP_PORT_LABEL

        val sourceLabel = when (mode) {
            SshCollector.MODE_WIN_SERVER -> "Windows Server (BETA)"
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
