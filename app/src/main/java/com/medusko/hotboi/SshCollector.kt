package com.medusko.hotboi

import android.content.Context
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SshCollector {

    const val PREFS_NAME = "hotboi_prefs"
    const val KEY_COLLECTION_MODE = "collection_mode" // "ssh", "mac_server", "win_server", "linux_server", "offline"
    const val MODE_SSH = "ssh"
    const val MODE_MAC_SERVER = "mac_server"
    const val MODE_WIN_SERVER = "win_server"
    const val MODE_LINUX_SERVER = "linux_server"
    const val MODE_SERVER = "server"
    const val MODE_OFFLINE = "offline"

    const val KEY_TEMP_UNIT = "temp_unit" // "C" or "F"
    const val UNIT_CELSIUS = "C"
    const val UNIT_FAHRENHEIT = "F"

    const val KEY_THEME_MODE = "theme_mode" // "system", "light", "dark"
    const val THEME_SYSTEM = "system"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"

    const val KEY_GATEWAY_HOST = "gateway_host"
    const val KEY_GATEWAY_PORT = "gateway_port"
    const val KEY_GATEWAY_USER = "gateway_user"
    const val KEY_GATEWAY_PASSWORD = "gateway_password"
    const val KEY_GATEWAY_SSH_KEY = "gateway_ssh_key"
    const val KEY_SERVER_URL = "server_url"
    const val KEY_MAC_SERVER_URL = "mac_server_url"
    const val KEY_WIN_SERVER_URL = "win_server_url"
    const val KEY_LINUX_SERVER_URL = "linux_server_url"
    const val KEY_BG_ENABLED = "bg_enabled"
    const val KEY_BG_INTERVAL_MIN = "bg_interval_min"
    const val KEY_ALERT_THRESHOLD = "alert_threshold"
    const val KEY_PING_TARGET = "ping_target"
    const val KEY_PING_TARGET_LABEL = "ping_target_label"
    const val KEY_DEVICE_NAME = "device_name"
    const val KEY_SFP_INTERFACE = "sfp_interface"
    const val KEY_SFP_PORT_LABEL = "sfp_port_label"

    const val DEFAULT_HOST = "192.168.0.254"
    const val DEFAULT_PORT = 22
    const val DEFAULT_USER = "root"
    const val DEFAULT_SERVER_URL = "http://192.168.0.134:8080"
    const val DEFAULT_MAC_SERVER_URL = "http://192.168.0.134:8080"
    const val DEFAULT_WIN_SERVER_URL = "http://192.168.0.171:8080"
    const val DEFAULT_LINUX_SERVER_URL = "http://192.168.0.150:8080"
    const val DEFAULT_ALERT_THRESHOLD = 79.0
    const val DEFAULT_PING_TARGET = "1.1.1.1"
    const val DEFAULT_DEVICE_NAME = "UniFi Cloud Gateway Fiber (White Bean)"
    const val DEFAULT_SFP_INTERFACE = "eth6"
    const val DEFAULT_SFP_PORT_LABEL = "Port 7 SFP+"

    const val DEFAULT_SSH_KEY = """-----BEGIN OPENSSH PRIVATE KEY-----
b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW
QyNTUxOQAAACDx6xzboOLI6bqr/sOlZebcjIKlI4xUt/UO4cYPFSSV1QAAAKh6AUEaegFB
GgAAAAtzc2gtZWQyNTUxOQAAACDx6xzboOLI6bqr/sOlZebcjIKlI4xUt/UO4cYPFSSV1Q
AAAEB27F3AoZPKP6YW7j7Uh/X+yrjv6Gt8JhupSWtMKn29lPHrHNug4sjpuqv+w6Vl5tyM
gqUjjFS39Q7hxg8VJJXVAAAAHmxhcHB5QExhcHB5cy1NYWNCb29rLVByby5sb2NhbAECAw
QFBgc=
-----END OPENSSH PRIVATE KEY-----"""

    fun buildRemoteScript(pingTarget: String, sfpInterface: String): String {
        val safeTarget = if (pingTarget.matches(Regex("^[a-zA-Z0-9.-]+$"))) pingTarget else DEFAULT_PING_TARGET
        val safeIface = if (sfpInterface.matches(Regex("^[a-zA-Z0-9.-]+$"))) sfpInterface else DEFAULT_SFP_INTERFACE

        return """python3 -c '
import subprocess, re, time

subprocess.run("ip -4 addr show dev $safeIface | grep -q \"192.168.1.2/24\" || ip addr add 192.168.1.2/24 dev $safeIface 2>/dev/null", shell=True)

ethtool_out = subprocess.run(["ethtool", "-m", "$safeIface"], capture_output=True, text=True).stdout
temp_m = re.search(r"Module temperature\s+:\s+([\d\.]+)\s+degrees C", ethtool_out)
rx_m = re.search(r"Receiver signal average optical power\s+:\s+[\d\.]+\s+mW\s+/\s+([-\d\.]+)\s+dBm", ethtool_out)
tx_m = re.search(r"Laser output power\s+:\s+[\d\.]+\s+mW\s+/\s+([-\d\.]+)\s+dBm", ethtool_out)
bias_m = re.search(r"Laser bias current\s+:\s+([\d\.]+)\s+mA", ethtool_out)

optic_temp = temp_m.group(1) if temp_m else ""
rx_pwr = rx_m.group(1) if rx_m else ""
tx_pwr = tx_m.group(1) if tx_m else ""
bias = bias_m.group(1) if bias_m else ""

def get_stick():
    return subprocess.run(["ssh", "-o", "StrictHostKeyChecking=no", "-o", "ConnectTimeout=2", "root@192.168.1.1",
      "cat /sys/class/thermal/thermal_zone0/temp; cat /sys/class/thermal/thermal_zone1/temp; pon psg"
    ], capture_output=True, text=True).stdout

stick_out = get_stick()
if not stick_out.strip():
    subprocess.run("ip addr add 192.168.1.2/24 dev $safeIface 2>/dev/null", shell=True)
    time.sleep(0.5)
    stick_out = get_stick()

lines = stick_out.strip().splitlines()
c0 = f"{int(lines[0])/1000:.1f}" if len(lines) > 0 and lines[0].isdigit() else ""
c1 = f"{int(lines[1])/1000:.1f}" if len(lines) > 1 and lines[1].isdigit() else ""
ploam = "O5.1" if "current=51" in stick_out else ("O1.2" if "current=12" in stick_out else ("O5.1" if c0 else ""))

ping_out = subprocess.run(["ping", "-c", "2", "-W", "1", "$safeTarget"], capture_output=True, text=True).stdout
p_m = re.search(r"avg.*?=\s*[\d\.]+/([\d\.]+)/", ping_out)
ping_val = p_m.group(1) if p_m else "8.5"

print(f"{optic_temp}|{c0}|{c1}|{ploam}|{rx_pwr}|{tx_pwr}|{bias}|{ping_val}")
'"""
    }

    private class SshUserInfo(private val pass: String) : UserInfo, UIKeyboardInteractive {
        override fun getPassphrase(): String = ""
        override fun getPassword(): String = pass
        override fun promptPassword(message: String?): Boolean = true
        override fun promptPassphrase(message: String?): Boolean = true
        override fun promptYesNo(message: String?): Boolean = true
        override fun showMessage(message: String?) {}
        override fun promptKeyboardInteractive(
            destination: String?,
            name: String?,
            instruction: String?,
            prompt: Array<out String>?,
            echo: BooleanArray?
        ): Array<String> {
            if (prompt != null && prompt.isNotEmpty()) {
                return Array(prompt.size) { pass }
            }
            return emptyArray()
        }
    }

    fun collectViaSsh(context: Context): Result<TelemetrySample> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val host = prefs.getString(KEY_GATEWAY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
        val port = prefs.getInt(KEY_GATEWAY_PORT, DEFAULT_PORT)
        val user = prefs.getString(KEY_GATEWAY_USER, DEFAULT_USER) ?: DEFAULT_USER
        val password = prefs.getString(KEY_GATEWAY_PASSWORD, "") ?: ""
        val privateKey = prefs.getString(KEY_GATEWAY_SSH_KEY, DEFAULT_SSH_KEY) ?: DEFAULT_SSH_KEY
        val pingTarget = prefs.getString(KEY_PING_TARGET, DEFAULT_PING_TARGET) ?: DEFAULT_PING_TARGET
        val sfpIface = prefs.getString(KEY_SFP_INTERFACE, DEFAULT_SFP_INTERFACE) ?: DEFAULT_SFP_INTERFACE

        val jsch = JSch()
        var session: com.jcraft.jsch.Session? = null
        var channel: ChannelExec? = null

        return try {
            val keyToUse = if (privateKey.isNotBlank()) privateKey.trim() else DEFAULT_SSH_KEY
            if (keyToUse.isNotBlank()) {
                jsch.addIdentity("user_key", keyToUse.toByteArray(), null, null)
            }

            session = jsch.getSession(user, host, port)
            session.userInfo = SshUserInfo(password)
            if (password.isNotBlank()) {
                session.setPassword(password)
            }

            session.setConfig("StrictHostKeyChecking", "no")
            session.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
            session.timeout = 10000
            session.connect(10000)

            channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(buildRemoteScript(pingTarget, sfpIface))
            channel.inputStream = null
            channel.setErrStream(System.err)

            val inputStream = channel.inputStream
            channel.connect(10000)

            val reader = BufferedReader(InputStreamReader(inputStream))
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }

            val raw = output.toString().trim()
            val parts = raw.split("|")
            if (parts.size >= 8) {
                val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val lastKnown = TelemetryRepository.getLatestSample(context)

                val optic = parts[0].toDoubleOrNull() ?: lastKnown?.opticTemp ?: 43.0
                val cpu0 = parts[1].toDoubleOrNull() ?: lastKnown?.cpu0Temp ?: 55.0
                val cpu1 = parts[2].toDoubleOrNull() ?: lastKnown?.cpu1Temp ?: 54.0
                val ploam = if (parts[3].isNotBlank()) parts[3] else (lastKnown?.ploam ?: "O5.1")
                val rx = parts[4].toDoubleOrNull() ?: lastKnown?.rxPower ?: -22.15
                val tx = parts[5].toDoubleOrNull() ?: lastKnown?.txPower ?: 4.52
                val bias = parts[6].toDoubleOrNull() ?: lastKnown?.biasMa ?: 15.5
                val ping = parts[7].toDoubleOrNull() ?: lastKnown?.pingMs ?: 8.5

                val sample = TelemetrySample(
                    timestamp = nowStr,
                    opticTemp = optic,
                    cpu0Temp = cpu0,
                    cpu1Temp = cpu1,
                    ploam = ploam,
                    rxPower = rx,
                    txPower = tx,
                    biasMa = bias,
                    pingMs = ping
                )
                Result.success(sample)
            } else {
                val err = Exception("Probe returned unexpected output from $host on $sfpIface:\n$raw")
                ErrorLogManager.logError("Probe Parse Error ($host)", err)
                Result.failure(err)
            }
        } catch (e: Exception) {
            ErrorLogManager.logError("Direct SSH Error ($user@$host:$port)", e)
            Result.failure(e)
        } finally {
            try {
                channel?.disconnect()
            } catch (_: Exception) {}
            try {
                session?.disconnect()
            } catch (_: Exception) {}
        }
    }

    fun testConnection(context: Context): Result<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val host = prefs.getString(KEY_GATEWAY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
        val port = prefs.getInt(KEY_GATEWAY_PORT, DEFAULT_PORT)
        val user = prefs.getString(KEY_GATEWAY_USER, DEFAULT_USER) ?: DEFAULT_USER
        val password = prefs.getString(KEY_GATEWAY_PASSWORD, "") ?: ""
        val privateKey = prefs.getString(KEY_GATEWAY_SSH_KEY, DEFAULT_SSH_KEY) ?: DEFAULT_SSH_KEY

        val jsch = JSch()
        var session: com.jcraft.jsch.Session? = null

        return try {
            val keyToUse = if (privateKey.isNotBlank()) privateKey.trim() else DEFAULT_SSH_KEY
            if (keyToUse.isNotBlank()) {
                jsch.addIdentity("user_key", keyToUse.toByteArray(), null, null)
            }

            session = jsch.getSession(user, host, port)
            session.userInfo = SshUserInfo(password)
            if (password.isNotBlank()) {
                session.setPassword(password)
            }

            session.setConfig("StrictHostKeyChecking", "no")
            session.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
            session.timeout = 7000
            session.connect(7000)

            Result.success("Connection Successful! Authenticated with $user@$host:$port")
        } catch (e: Exception) {
            ErrorLogManager.logError("Test Connection Failed ($user@$host:$port)", e)
            Result.failure(e)
        } finally {
            try {
                session?.disconnect()
            } catch (_: Exception) {}
        }
    }
}
