package com.medusko.hotboi

import android.content.Context
import android.content.res.Configuration
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader

/**
 * Append-only JSONL persistence repository providing source- and interface-isolated telemetry caching.
 * - Appends new samples in O(1) via single-line JSONL writes instead of rewriting 2,500-sample arrays.
 * - Automatically migrates legacy `.json` array caches on first load without data loss.
 * - Compacts JSONL files atomically only when line count exceeds [COMPACT_THRESHOLD].
 * - Provides both full initial bundle JSON and ~300-byte incremental delta JSON for WebView updates.
 */
object TelemetryRepository {

    const val MAX_SAMPLES = 2500
    private const val COMPACT_THRESHOLD = 3000

    private val gson get() = CollectorHttpClient.gson
    private val modeCaches = mutableMapOf<String, MutableList<TelemetrySample>>()
    private val fileLineCounts = mutableMapOf<String, Int>()

    fun resolveMode(context: Context, mode: String?): String {
        if (!mode.isNullOrBlank()) return mode
        val prefs = context.getSharedPreferences(SshCollector.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(SshCollector.KEY_COLLECTION_MODE, SshCollector.MODE_SSH) ?: SshCollector.MODE_SSH
    }

    fun resolveCacheKey(context: Context, mode: String): String {
        if (mode == SshCollector.MODE_SSH) {
            val prefs = context.getSharedPreferences(SshCollector.PREFS_NAME, Context.MODE_PRIVATE)
            val rawIface = prefs.getString(SshCollector.KEY_SFP_INTERFACE, SshCollector.DEFAULT_SFP_INTERFACE)
                ?: SshCollector.DEFAULT_SFP_INTERFACE
            val safeIface = rawIface.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { SshCollector.DEFAULT_SFP_INTERFACE }
            return "${SshCollector.MODE_SSH}_$safeIface"
        }
        return mode
    }

    private fun getJsonlFileName(cacheKey: String): String {
        return getLegacyJsonFileName(cacheKey).removeSuffix(".json") + ".jsonl"
    }

    private fun getLegacyJsonFileName(cacheKey: String): String {
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

        val jsonlFile = File(context.filesDir, getJsonlFileName(cacheKey))
        val legacyJsonFile = File(context.filesDir, getLegacyJsonFileName(cacheKey))
        val loadedList = mutableListOf<TelemetrySample>()
        var needsFullCompact = false
        var lineCount = 0

        if (jsonlFile.exists()) {
            try {
                jsonlFile.useLines(Charsets.UTF_8) { lines ->
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.isEmpty()) continue
                        lineCount++
                        try {
                            val sample = gson.fromJson(trimmed, TelemetrySample::class.java)
                            if (sample != null && (mode == SshCollector.MODE_OFFLINE || !isBundledSeedSample(sample))) {
                                loadedList.add(sample)
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
                if (loadedList.size > MAX_SAMPLES) {
                    val trim = loadedList.size - MAX_SAMPLES
                    loadedList.subList(0, trim).clear()
                    needsFullCompact = true
                }
            } catch (_: Exception) {
            }
        } else if (legacyJsonFile.exists()) {
            // One-time migration from legacy JSON array to append-only JSONL
            try {
                val json = legacyJsonFile.readText(Charsets.UTF_8)
                val type = object : TypeToken<List<TelemetrySample>>() {}.type
                val list: List<TelemetrySample> = gson.fromJson(json, type) ?: emptyList()
                val filtered = if (mode == SshCollector.MODE_OFFLINE) list else list.filterNot { isBundledSeedSample(it) }
                loadedList.addAll(filtered.takeLast(MAX_SAMPLES))
                needsFullCompact = true
            } catch (_: Exception) {
            }
        }

        if (loadedList.isEmpty() && cacheKey == "${SshCollector.MODE_SSH}_eth6") {
            val legacySshFile = File(context.filesDir, "direct_ssh_history.json")
            if (legacySshFile.exists()) {
                try {
                    val json = legacySshFile.readText(Charsets.UTF_8)
                    val type = object : TypeToken<List<TelemetrySample>>() {}.type
                    val list: List<TelemetrySample> = gson.fromJson(json, type) ?: emptyList()
                    val liveOnly = list.filterNot { isBundledSeedSample(it) }.takeLast(MAX_SAMPLES)
                    if (liveOnly.isNotEmpty()) {
                        loadedList.addAll(liveOnly)
                        needsFullCompact = true
                    }
                } catch (_: Exception) {
                }
            }
        }

        if (loadedList.isEmpty() && mode == SshCollector.MODE_OFFLINE) {
            seedFromAssets(context, loadedList)
            needsFullCompact = true
        }

        if (needsFullCompact) {
            compactJsonl(context, cacheKey, loadedList)
            if (legacyJsonFile.exists() && jsonlFile.exists()) {
                legacyJsonFile.delete()
            }
        } else {
            fileLineCounts[cacheKey] = lineCount
        }

        modeCaches[cacheKey] = loadedList
    }

    private fun seedFromAssets(context: Context, targetList: MutableList<TelemetrySample>) {
        try {
            context.assets.open("initial_telemetry.csv").use { inputStream ->
                InputStreamReader(inputStream, Charsets.UTF_8).useLines { lines ->
                    val iterator = lines.iterator()
                    if (iterator.hasNext()) iterator.next()
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
        } catch (_: Exception) {
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

        val currentLines = (fileLineCounts[cacheKey] ?: list.size) + 1
        if (currentLines > COMPACT_THRESHOLD) {
            compactJsonl(context, cacheKey, list)
        } else {
            appendSingleLine(context, cacheKey, sample)
            fileLineCounts[cacheKey] = currentLines
        }
    }

    private fun appendSingleLine(context: Context, cacheKey: String, sample: TelemetrySample) {
        try {
            val file = File(context.filesDir, getJsonlFileName(cacheKey))
            val lineBytes = (gson.toJson(sample) + "\n").toByteArray(Charsets.UTF_8)
            FileOutputStream(file, true).use { out ->
                out.write(lineBytes)
            }
        } catch (_: Exception) {
        }
    }

    private fun compactJsonl(context: Context, cacheKey: String, list: List<TelemetrySample>) {
        try {
            val fileName = getJsonlFileName(cacheKey)
            val file = File(context.filesDir, fileName)
            val tmpFile = File(context.filesDir, "$fileName.tmp")
            tmpFile.bufferedWriter(Charsets.UTF_8).use { writer ->
                for (s in list) {
                    writer.write(gson.toJson(s))
                    writer.newLine()
                }
            }
            if (!tmpFile.renameTo(file)) {
                tmpFile.copyTo(file, overwrite = true)
                tmpFile.delete()
            }
            fileLineCounts[cacheKey] = list.size
        } catch (_: Exception) {
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
        val mode = resolveMode(context, explicitMode)
        val cacheKey = resolveCacheKey(context, mode)
        initialize(context, mode)
        return modeCaches[cacheKey]?.lastOrNull()
    }

    private fun formatShortTimeLabel(timestamp: String): String {
        return if (timestamp.contains(" ")) {
            val t = timestamp.split(" ")[1]
            t.substring(0, minOf(5, t.length))
        } else {
            timestamp
        }
    }

    private fun buildDisplayMetadata(context: Context, mode: String): Map<String, Any> {
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
                val nightModeFlags = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                nightModeFlags != Configuration.UI_MODE_NIGHT_YES
            }
        }

        return mapOf(
            "deviceName" to deviceName,
            "portName" to portName,
            "pingTarget" to pingTargetLabel,
            "mode" to mode,
            "sourceLabel" to sourceLabel,
            "tempUnit" to tempUnit,
            "threshold" to thresholdC,
            "isLight" to isLight
        )
    }

    @Synchronized
    fun toJsonBundle(context: Context, explicitMode: String? = null): String {
        val mode = resolveMode(context, explicitMode)
        val samples = getAllSamples(context, mode)
        val latest = samples.lastOrNull()
        val size = samples.size
        val labels = ArrayList<String>(size)
        val fullTimestamps = ArrayList<String>(size)
        val opticData = ArrayList<Double>(size)
        val cpu0Data = ArrayList<Double>(size)
        val cpu1Data = ArrayList<Double>(size)
        val pingData = ArrayList<Double>(size)

        for (s in samples) {
            fullTimestamps.add(s.timestamp)
            labels.add(formatShortTimeLabel(s.timestamp))
            opticData.add(s.opticTemp)
            cpu0Data.add(s.cpu0Temp)
            cpu1Data.add(s.cpu1Temp)
            pingData.add(s.pingMs)
        }

        val bundle = LinkedHashMap<String, Any?>().apply {
            put("type", "bundle")
            put("latest", latest)
            put("sampleCount", size)
            put("labels", labels)
            put("fullTimestamps", fullTimestamps)
            put("opticData", opticData)
            put("cpu0Data", cpu0Data)
            put("cpu1Data", cpu1Data)
            put("pingData", pingData)
            putAll(buildDisplayMetadata(context, mode))
        }

        return gson.toJson(bundle)
    }

    /**
     * Serializes a lightweight single-sample incremental delta (~300 bytes) for O(1) WebView chart updates.
     */
    @Synchronized
    fun toIncrementalDeltaJson(context: Context, sample: TelemetrySample, explicitMode: String? = null): String {
        val mode = resolveMode(context, explicitMode)
        val cacheKey = resolveCacheKey(context, mode)
        val count = modeCaches[cacheKey]?.size ?: 1
        val delta = LinkedHashMap<String, Any?>().apply {
            put("type", "delta")
            put("sample", sample)
            put("timeLabel", formatShortTimeLabel(sample.timestamp))
            put("sampleCount", count)
            put("maxSamples", MAX_SAMPLES)
            putAll(buildDisplayMetadata(context, mode))
        }
        return gson.toJson(delta)
    }
}
