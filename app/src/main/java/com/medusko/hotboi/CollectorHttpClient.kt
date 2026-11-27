package com.medusko.hotboi

import android.content.Context
import com.google.gson.Gson
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale

/**
 * Shared, security-hardened HTTP client & JSON serializer for companion server communication.
 * - Shares a single [Gson] instance across UI and background workers.
 * - Enforces HTTPS for all public/WAN hosts, allowing cleartext HTTP strictly to private RFC1918,
 *   loopback, link-local, or mDNS (.local / .lan / .home.arpa) LAN addresses.
 * - Attaches optional `X-HotBoi-Token` authentication header decrypted from Keystore prefs.
 */
object CollectorHttpClient {

    val gson: Gson = Gson()

    private val IPV4_REGEX = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    /**
     * Returns true if [host] is a loopback, RFC1918 private LAN, link-local, ULA IPv6, or local mDNS host.
     */
    fun isPrivateOrLocalHost(rawHost: String?): Boolean {
        val host = rawHost?.trim()?.lowercase(Locale.US)?.removeSurrounding("[", "]") ?: return false
        if (host.isEmpty()) return false
        if (host == "localhost" || host == "::1" || host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home.arpa") || host.endsWith(".internal")) {
            return true
        }
        // IPv6 Unique Local (fc00::/7) or Link-Local (fe80::/10)
        if (host.contains(":")) {
            return host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80:")
        }
        val match = IPV4_REGEX.matchEntire(host) ?: return false
        val octets = match.groupValues.drop(1).map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        val (a, b) = octets[0] to octets[1]
        return when {
            a == 127 -> true // Loopback 127.0.0.0/8
            a == 10 -> true // RFC1918 10.0.0.0/8
            a == 172 && b in 16..31 -> true // RFC1918 172.16.0.0/12
            a == 192 && b == 168 -> true // RFC1918 192.168.0.0/16
            a == 169 && b == 254 -> true // Link-local 169.254.0.0/16
            a == 100 && b in 64..127 -> true // Tailscale / CGNAT 100.64.0.0/10
            else -> false
        }
    }

    /**
     * Validates a collector base URL and ensures WAN hosts strictly use HTTPS.
     */
    fun validateCollectorBaseUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim().trimEnd('/')
        val uri = try {
            URI(trimmed)
        } catch (e: Exception) {
            throw SecurityException("Invalid server URL: $trimmed")
        }
        val scheme = uri.scheme?.lowercase(Locale.US)
            ?: throw SecurityException("Server URL must start with http:// (LAN only) or https://")
        val host = uri.host ?: throw SecurityException("Server URL is missing a valid hostname or IP")

        if (scheme == "https") return trimmed
        if (scheme == "http") {
            if (!isPrivateOrLocalHost(host)) {
                throw SecurityException(
                    "Cleartext HTTP to public host '$host' is blocked for security. Use https:// for remote/WAN servers, or connect via LAN/VPN IP."
                )
            }
            return trimmed
        }
        throw SecurityException("Unsupported URL scheme '$scheme' (only http:// on LAN or https:// allowed)")
    }

    fun resolveServerUrlForMode(context: Context, mode: String): String {
        val prefs = context.getSharedPreferences(SshCollector.PREFS_NAME, Context.MODE_PRIVATE)
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
        return prefs.getString(prefKey, defaultUrl)?.trim().takeUnless { it.isNullOrEmpty() } ?: defaultUrl
    }

    private fun getSavedApiToken(context: Context): String {
        val prefs = context.getSharedPreferences(SshCollector.PREFS_NAME, Context.MODE_PRIVATE)
        return CryptoHelper.decrypt(prefs.getString(SshCollector.KEY_SERVER_API_TOKEN, "")).trim()
    }

    private fun openAuthenticatedConnection(
        context: Context,
        endpointUrl: String,
        method: String = "GET",
        timeoutMs: Int = 4000
    ): HttpURLConnection {
        val conn = (URL(endpointUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            val token = getSavedApiToken(context)
            if (token.isNotEmpty()) {
                setRequestProperty("X-HotBoi-Token", token)
            }
        }
        return conn
    }

    fun fetchTelemetrySample(context: Context, rawServerUrl: String): Result<Pair<String, TelemetrySample>> {
        var conn: HttpURLConnection? = null
        return try {
            val base = validateCollectorBaseUrl(rawServerUrl)
            val endpoint = if (base.endsWith("/api/telemetry")) base else "$base/api/telemetry"
            conn = openAuthenticatedConnection(context, endpoint, method = "GET", timeoutMs = 4000)
            val code = conn.responseCode
            if (code == 200) {
                val json = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val sample = gson.fromJson(json, TelemetrySample::class.java)
                    ?: throw IllegalStateException("Empty telemetry JSON response from $endpoint")
                val normalizedBase = base.removeSuffix("/api/telemetry")
                Result.success(normalizedBase to sample)
            } else if (code == 401 || code == 403) {
                Result.failure(SecurityException("HTTP $code Unauthorized: Check Server API Token in Settings"))
            } else {
                Result.failure(Exception("HTTP $code: ${conn.responseMessage}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            conn?.disconnect()
        }
    }

    fun fetchServerPingConfig(context: Context, validatedBase: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = openAuthenticatedConnection(context, "$validatedBase/api/config", method = "GET", timeoutMs = 2000)
            if (conn.responseCode == 200) {
                val configJson = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                JSONObject(configJson).optString("ping_target", "").takeIf { it.isNotEmpty() }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    fun pushServerPingConfig(context: Context, rawServerBase: String, pingTarget: String): Result<Int> {
        var conn: HttpURLConnection? = null
        return try {
            val base = validateCollectorBaseUrl(rawServerBase)
            conn = openAuthenticatedConnection(context, "$base/api/config", method = "POST", timeoutMs = 4000).apply {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            val payload = JSONObject().apply { put("ping_target", pingTarget) }.toString()
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            Result.success(conn.responseCode)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            conn?.disconnect()
        }
    }
}
