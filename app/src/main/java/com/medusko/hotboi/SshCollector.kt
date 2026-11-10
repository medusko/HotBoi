package com.medusko.hotboi

import android.content.Context
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DiscoveredPort(
    val iface: String,
    val operState: String,
    val hasOpticDom: Boolean,
    val opticTempC: Double?
) {
    fun toDisplayLabel(): String {
        val stateUpper = operState.uppercase(Locale.US)
        return if (hasOpticDom && opticTempC != null) {
            String.format(Locale.US, "%s — %s • Optical DOM (%.1f°C)", iface, stateUpper, opticTempC)
        } else if (hasOpticDom) {
            "$iface — $stateUpper • Optical Transceiver"
        } else {
            "$iface — $stateUpper"
        }
    }
}

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
    const val DEFAULT_DEVICE_NAME = "Primary Network Gateway"
    const val DEFAULT_SFP_INTERFACE = "eth6"
    const val DEFAULT_SFP_PORT_LABEL = "Port 7 SFP+ (eth6)"

    private val SAFE_HOST_REGEX = Regex("^[a-zA-Z0-9][a-zA-Z0-9.:-]{0,252}$")
    private val SAFE_IFACE_REGEX = Regex("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,31}$")

    fun buildRemoteScript(pingTarget: String, sfpInterface: String): String {
        val safeTarget = if (pingTarget.matches(SAFE_HOST_REGEX)) pingTarget else DEFAULT_PING_TARGET
        val safeIface = if (sfpInterface.matches(SAFE_IFACE_REGEX)) sfpInterface else DEFAULT_SFP_INTERFACE

        return """python3 -c '
import subprocess, re, os, glob, time

iface = "$safeIface"
target = "$safeTarget"

ping_proc = subprocess.Popen(["ping", "-c", "2", "-W", "1", target], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)

net_path = f"/sys/class/net/{iface}"
if not os.path.exists(net_path):
    try:
        ping_proc.kill()
    except Exception:
        pass
    print(f"NO_IFACE|{iface}")
    raise SystemExit(0)

try:
    operstate = open(f"{net_path}/operstate").read().strip()
except Exception:
    operstate = "unknown"

try:
    carrier = open(f"{net_path}/carrier").read().strip()
except Exception:
    carrier = "0"

# 1. Read SFP/SFP+ DOM optical diagnostics via ethtool -m on the selected interface
ethtool_out = subprocess.run(["ethtool", "-m", iface], capture_output=True, text=True).stdout
temp_m = re.search(r"Module temperature\s+:\s+([-\d\.]+)\s+degrees C", ethtool_out)
rx_m = re.search(r"Receiver signal average optical power\s+:\s+[\d\.]+\s+mW\s+/\s+([-\d\.]+)\s+dBm", ethtool_out)
if not rx_m:
    rx_m = re.search(r"Rx power\s+:\s+[\d\.]+\s+mW\s+/\s+([-\d\.]+)\s+dBm", ethtool_out)
tx_m = re.search(r"Laser output power\s+:\s+[\d\.]+\s+mW\s+/\s+([-\d\.]+)\s+dBm", ethtool_out)
if not tx_m:
    tx_m = re.search(r"Laser transmit average optical power\s+:\s+[\d\.]+\s+mW\s+/\s+([-\d\.]+)\s+dBm", ethtool_out)
bias_m = re.search(r"Laser bias current\s+:\s+([\d\.]+)\s+mA", ethtool_out)

optic_temp = temp_m.group(1) if temp_m else ""
rx_pwr = rx_m.group(1) if rx_m else ""
tx_pwr = tx_m.group(1) if tx_m else ""
bias = bias_m.group(1) if bias_m else ""

# Fallback to kernel hwmon sensor bound specifically to this network interface if present
if not optic_temp:
    for p in glob.glob(f"{net_path}/device/hwmon/hwmon*/temp1_input"):
        try:
            v = int(open(p).read().strip())
            if v > 0:
                optic_temp = f"{v/1000.0:.1f}"
                break
        except Exception:
            pass

# 2. Check if an ONU / PON stick (192.168.1.1) is reachable specifically on THIS interface
all_ip_out = subprocess.run(["ip", "-4", "-o", "addr", "show"], capture_output=True, text=True).stdout
iface_has_stick_subnet = False
other_iface_has_stick_subnet = False
for ln in all_ip_out.splitlines():
    if "192.168.1.2/" in ln:
        if f" {iface} " in ln:
            iface_has_stick_subnet = True
        else:
            other_iface_has_stick_subnet = True

if not iface_has_stick_subnet and not other_iface_has_stick_subnet and (optic_temp or carrier == "1" or operstate == "up"):
    subprocess.run(["ip", "addr", "add", "192.168.1.2/24", "dev", iface], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    iface_has_stick_subnet = True

c0, c1, ploam = "", "", ""
if iface_has_stick_subnet:
    # Verify 192.168.1.1 responds specifically via this interface
    stick_ping = subprocess.run(["ping", "-I", iface, "-c", "1", "-W", "1", "192.168.1.1"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if stick_ping.returncode == 0:
        stick_out = subprocess.run([
            "ssh", "-o", "StrictHostKeyChecking=no", "-o", "ConnectTimeout=2", "-b", "192.168.1.2", "root@192.168.1.1",
            "cat /sys/class/thermal/thermal_zone0/temp; cat /sys/class/thermal/thermal_zone1/temp; pon psg"
        ], capture_output=True, text=True).stdout
        lines = stick_out.strip().splitlines()
        c0 = f"{int(lines[0])/1000:.1f}" if len(lines) > 0 and lines[0].isdigit() else ""
        c1 = f"{int(lines[1])/1000:.1f}" if len(lines) > 1 and lines[1].isdigit() else ""
        ploam = "O5.1" if "current=51" in stick_out else ("O1.2" if "current=12" in stick_out else ("O5.1" if c0 else ""))

# 3. If no optical DOM temperature AND no PON stick responded on this interface,
# check if the interface has an active module/carrier or is completely empty/unplugged
if not optic_temp and not c0:
    if carrier != "1" and operstate != "up":
        try:
            ping_proc.kill()
        except Exception:
            pass
        print(f"NO_MODULE|{iface}|{operstate}")
        raise SystemExit(0)

# 4. Universal Gateway CPU thermal fallback (for standard non-PON SFP+ modules or gateways without a stick at 192.168.1.1)
if not c0:
    gw_temps = []
    for tz in sorted(glob.glob("/sys/class/thermal/thermal_zone*/temp")):
        try:
            val = int(open(tz).read().strip())
            if 10000 <= val <= 125000:
                gw_temps.append(val / 1000.0)
        except Exception:
            pass
    if not gw_temps:
        for hw in sorted(glob.glob("/sys/class/hwmon/hwmon*/temp*_input")):
            try:
                val = int(open(hw).read().strip())
                if 10000 <= val <= 125000:
                    gw_temps.append(val / 1000.0)
            except Exception:
                pass
    if len(gw_temps) >= 1:
        c0 = f"{gw_temps[0]:.1f}"
    if len(gw_temps) >= 2:
        c1 = f"{gw_temps[1]:.1f}"
    elif c0:
        c1 = c0

if not optic_temp and c0:
    optic_temp = c0

if not ploam:
    ploam = "LINK UP" if (carrier == "1" or operstate == "up") else "STANDBY"

try:
    ping_out = ping_proc.communicate(timeout=4)[0] or ""
except Exception:
    ping_proc.kill()
    ping_out = ""
p_m = re.search(r"avg.*?=\s*[\d\.]+/([\d\.]+)/", ping_out)
ping_val = p_m.group(1) if p_m else "0.0"

print(f"{optic_temp}|{c0}|{c1}|{ploam}|{rx_pwr}|{tx_pwr}|{bias}|{ping_val}")
'"""
    }

    private fun buildDiscoveryScript(): String {
        return """python3 -c '
import os, glob, subprocess, re

ports = []
for p in sorted(glob.glob("/sys/class/net/*")):
    iface = os.path.basename(p)
    if iface == "lo" or iface.startswith(("veth", "br", "docker", "tun", "wg", "sit", "ip6", "gre", "erspan", "ifb", "teql", " wlan", "ra", "rai", "ap")):
        continue
    if not iface.startswith(("eth", "sfp", "en", "swp", "xe", "ten", "opt", "lan", "wan")):
        continue
    try:
        state = open(f"{p}/operstate").read().strip()
    except Exception:
        state = "unknown"
    out = subprocess.run(["ethtool", "-m", iface], capture_output=True, text=True, timeout=2).stdout
    m = re.search(r"Module temperature\s+:\s+([-\d\.]+)\s+degrees C", out)
    has_dom = "1" if ("Module temperature" in out or "Laser" in out or "Transceiver" in out or "Identifier" in out) else "0"
    temp = m.group(1) if m else ""
    print(f"PORT|{iface}|{state}|{has_dom}|{temp}")
'"""
    }

    private class SshUserInfo(private val pass: String) : UserInfo, UIKeyboardInteractive {
        override fun getPassphrase(): String = ""
        override fun getPassword(): String = pass
        override fun promptPassword(message: String?): Boolean = true
        override fun promptPassphrase(message: String?): Boolean = true
        override fun promptYesNo(message: String?): Boolean {
            // Reject immediately if the remote host identification has changed (MITM protection)
            if (message != null && (
                    message.contains("HostKey has been changed", ignoreCase = true) ||
                    message.contains("REMOTE HOST IDENTIFICATION HAS CHANGED", ignoreCase = true) ||
                    message.contains("offending key", ignoreCase = true)
                )) {
                return false
            }
            // Trust On First Use: Accept new unknown host key
            return true
        }
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

    private fun configureKnownHosts(context: Context, jsch: JSch, session: com.jcraft.jsch.Session) {
        val knownHostsFile = java.io.File(context.filesDir, "ssh_known_hosts")
        if (!knownHostsFile.exists()) {
            try {
                knownHostsFile.createNewFile()
            } catch (_: Exception) {}
        }
        if (knownHostsFile.exists()) {
            try {
                jsch.setKnownHosts(knownHostsFile.absolutePath)
                session.setConfig("StrictHostKeyChecking", "ask")
            } catch (_: Exception) {
                session.setConfig("StrictHostKeyChecking", "no")
            }
        } else {
            session.setConfig("StrictHostKeyChecking", "no")
        }
    }

    fun collectViaSsh(context: Context): Result<TelemetrySample> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val host = prefs.getString(KEY_GATEWAY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
        val port = prefs.getInt(KEY_GATEWAY_PORT, DEFAULT_PORT)
        val user = prefs.getString(KEY_GATEWAY_USER, DEFAULT_USER) ?: DEFAULT_USER
        val password = CryptoHelper.decrypt(prefs.getString(KEY_GATEWAY_PASSWORD, ""))
        val privateKey = CryptoHelper.decrypt(prefs.getString(KEY_GATEWAY_SSH_KEY, "") ?: "")
        val pingTarget = prefs.getString(KEY_PING_TARGET, DEFAULT_PING_TARGET) ?: DEFAULT_PING_TARGET
        val sfpIface = prefs.getString(KEY_SFP_INTERFACE, DEFAULT_SFP_INTERFACE) ?: DEFAULT_SFP_INTERFACE

        val jsch = JSch()
        var session: com.jcraft.jsch.Session? = null
        var channel: ChannelExec? = null

        return try {
            val keyToUse = privateKey.trim()
            if (keyToUse.isNotBlank()) {
                jsch.addIdentity("user_key", keyToUse.toByteArray(Charsets.UTF_8), null, null)
            }

            session = jsch.getSession(user, host, port)
            session.userInfo = SshUserInfo(password)
            if (password.isNotBlank()) {
                session.setPassword(password)
            }

            configureKnownHosts(context, jsch, session)
            session.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
            session.timeout = 10000
            session.connect(10000)

            channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(buildRemoteScript(pingTarget, sfpIface))
            channel.inputStream = null
            val errStream = ByteArrayOutputStream()
            channel.setErrStream(errStream)

            val inputStream = channel.inputStream
            channel.connect(10000)

            val raw = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                reader.readText().trim()
            }
            val stderrText = errStream.toString(Charsets.UTF_8.name()).trim()
            val dataLine = raw.lineSequence().map { it.trim() }.lastOrNull { it.contains("|") } ?: raw

            if (dataLine.startsWith("NO_IFACE|")) {
                val err = Exception("Interface '$sfpIface' does not exist on $host. Use 'Scan Gateway Ports' in Settings to select a valid interface.")
                ErrorLogManager.logError("Invalid Interface ($sfpIface on $host)", err)
                return Result.failure(err)
            }

            if (dataLine.startsWith("NO_MODULE|")) {
                val statePart = dataLine.split("|").getOrNull(2) ?: "down"
                val err = Exception("No SFP+ transceiver or active optical link detected on '$sfpIface' (port state: $statePart).")
                ErrorLogManager.logError("No Transceiver on $sfpIface ($host)", err)
                return Result.failure(err)
            }

            val parts = dataLine.split("|")
            if (parts.size >= 8) {
                val opticParsed = parts[0].toDoubleOrNull()
                val cpu0Parsed = parts[1].toDoubleOrNull()

                if (opticParsed == null && cpu0Parsed == null) {
                    val err = Exception("No thermal telemetry returned from '$sfpIface' on $host.")
                    ErrorLogManager.logError("Empty Telemetry ($sfpIface on $host)", err)
                    return Result.failure(err)
                }

                val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val optic = opticParsed ?: cpu0Parsed ?: 0.0
                val cpu0 = cpu0Parsed ?: opticParsed ?: 0.0
                val cpu1 = parts[2].toDoubleOrNull() ?: cpu0
                val ploam = if (parts[3].isNotBlank()) parts[3] else "ACTIVE"
                val rx = parts[4].toDoubleOrNull() ?: 0.0
                val tx = parts[5].toDoubleOrNull() ?: 0.0
                val bias = parts[6].toDoubleOrNull() ?: 0.0
                val ping = parts[7].toDoubleOrNull() ?: 0.0

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
                val errDetails = buildString {
                    append("Probe returned unexpected output from $host on $sfpIface:\n")
                    append(raw.ifEmpty { "<empty stdout>" })
                    if (stderrText.isNotEmpty()) {
                        append("\nStderr:\n")
                        append(stderrText)
                    }
                }
                val err = Exception(errDetails)
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

    fun discoverPorts(context: Context): Result<List<DiscoveredPort>> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val host = prefs.getString(KEY_GATEWAY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
        val port = prefs.getInt(KEY_GATEWAY_PORT, DEFAULT_PORT)
        val user = prefs.getString(KEY_GATEWAY_USER, DEFAULT_USER) ?: DEFAULT_USER
        val password = CryptoHelper.decrypt(prefs.getString(KEY_GATEWAY_PASSWORD, ""))
        val privateKey = CryptoHelper.decrypt(prefs.getString(KEY_GATEWAY_SSH_KEY, "") ?: "")

        val jsch = JSch()
        var session: com.jcraft.jsch.Session? = null
        var channel: ChannelExec? = null

        return try {
            val keyToUse = privateKey.trim()
            if (keyToUse.isNotBlank()) {
                jsch.addIdentity("user_key", keyToUse.toByteArray(Charsets.UTF_8), null, null)
            }

            session = jsch.getSession(user, host, port)
            session.userInfo = SshUserInfo(password)
            if (password.isNotBlank()) {
                session.setPassword(password)
            }

            configureKnownHosts(context, jsch, session)
            session.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
            session.timeout = 8000
            session.connect(8000)

            channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(buildDiscoveryScript())
            channel.inputStream = null
            val inputStream = channel.inputStream
            channel.connect(8000)

            val raw = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { it.readText().trim() }
            val list = mutableListOf<DiscoveredPort>()
            for (line in raw.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("PORT|")) {
                    val parts = trimmed.split("|")
                    if (parts.size >= 5) {
                        val iface = parts[1]
                        val state = parts[2]
                        val hasDom = parts[3] == "1"
                        val temp = parts[4].toDoubleOrNull()
                        list.add(DiscoveredPort(iface, state, hasDom, temp))
                    }
                }
            }
            // Sort ports with optical DOM first, then UP interfaces, then by name
            val sorted = list.sortedWith(
                compareByDescending<DiscoveredPort> { it.hasOpticDom }
                    .thenByDescending { it.operState.equals("up", ignoreCase = true) }
                    .thenBy { it.iface }
            )
            Result.success(sorted)
        } catch (e: Exception) {
            ErrorLogManager.logError("Port Scan Failed ($user@$host:$port)", e)
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
        val password = CryptoHelper.decrypt(prefs.getString(KEY_GATEWAY_PASSWORD, ""))
        val privateKey = CryptoHelper.decrypt(prefs.getString(KEY_GATEWAY_SSH_KEY, "") ?: "")

        val jsch = JSch()
        var session: com.jcraft.jsch.Session? = null

        return try {
            val keyToUse = privateKey.trim()
            if (keyToUse.isNotBlank()) {
                jsch.addIdentity("user_key", keyToUse.toByteArray(Charsets.UTF_8), null, null)
            }

            session = jsch.getSession(user, host, port)
            session.userInfo = SshUserInfo(password)
            if (password.isNotBlank()) {
                session.setPassword(password)
            }

            configureKnownHosts(context, jsch, session)
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
