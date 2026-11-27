package com.medusko.hotboi

import android.content.Context
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.security.MessageDigest
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
    const val KEY_PON_STICK_AUTO_IP = "pon_stick_auto_ip"
    const val KEY_SERVER_URL = "server_url"
    const val KEY_MAC_SERVER_URL = "mac_server_url"
    const val KEY_WIN_SERVER_URL = "win_server_url"
    const val KEY_LINUX_SERVER_URL = "linux_server_url"
    const val KEY_SERVER_API_TOKEN = "server_api_token"
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

    private const val KNOWN_HOSTS_FILENAME = "ssh_known_hosts"
    private val SAFE_HOST_REGEX = Regex("^[a-zA-Z0-9][a-zA-Z0-9.:-]{0,252}$")
    private val SAFE_IFACE_REGEX = Regex("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,31}$")

    // Pooled foreground SSH session state
    private val sessionLock = Any()
    private var pooledSession: Session? = null
    private var pooledSessionKey: String? = null

    /**
     * Pure POSIX `/bin/sh` + `awk` remote telemetry probe.
     * - Zero `python3` dependency (compatible with UniFi OS, OpenWrt, BusyBox, EdgeOS, Debian/Alpine).
     * - Runs single-packet ICMP ping concurrently with optical DOM read (~150 ms total execution).
     * - Never mutates router IP addresses (`192.168.1.2/24`) unless [allowPonStickAutoIp] is explicitly enabled.
     */
    fun buildRemoteScript(
        pingTarget: String,
        sfpInterface: String,
        allowPonStickAutoIp: Boolean = false
    ): String {
        val safeTarget = if (pingTarget.matches(SAFE_HOST_REGEX)) pingTarget else DEFAULT_PING_TARGET
        val safeIface = if (sfpInterface.matches(SAFE_IFACE_REGEX)) sfpInterface else DEFAULT_SFP_INTERFACE
        val autoPonFlag = if (allowPonStickAutoIp) "1" else "0"

        return """sh -c '
IFACE="$safeIface"
TARGET="$safeTarget"
AUTO_PON="$autoPonFlag"

net_path="/sys/class/net/${'$'}IFACE"
if [ ! -e "${'$'}net_path" ]; then
  echo "NO_IFACE|${'$'}IFACE"
  exit 0
fi

ping_tmp="/tmp/.hb_ping_${'$'}${'$'}"
(ping -c 1 -W 1 "${'$'}TARGET" > "${'$'}ping_tmp" 2>/dev/null) &
ping_pid=${'$'}!

operstate=$(cat "${'$'}net_path/operstate" 2>/dev/null || echo "unknown")
carrier=$(cat "${'$'}net_path/carrier" 2>/dev/null || echo "0")

eth_out=$(ethtool -m "${'$'}IFACE" 2>/dev/null || true)
optic_temp=$(printf "%s\n" "${'$'}eth_out" | awk -F":" "/Module temperature/ { if (match(\${'$'}2, /-?[0-9]+(\.[0-9]+)?/)) { print substr(\${'$'}2, RSTART, RLENGTH); exit } }")
rx_pwr=$(printf "%s\n" "${'$'}eth_out" | awk -F"/" "/(Receiver signal average optical power|Rx power)[[:space:]]*:/ { if (match(\${'$'}2, /-?[0-9]+(\.[0-9]+)?/)) { print substr(\${'$'}2, RSTART, RLENGTH); exit } }")
tx_pwr=$(printf "%s\n" "${'$'}eth_out" | awk -F"/" "/(Laser output power|Laser transmit average optical power)[[:space:]]*:/ { if (match(\${'$'}2, /-?[0-9]+(\.[0-9]+)?/)) { print substr(\${'$'}2, RSTART, RLENGTH); exit } }")
bias=$(printf "%s\n" "${'$'}eth_out" | awk -F":" "/Laser bias current/ { if (match(\${'$'}2, /[0-9]+(\.[0-9]+)?/)) { print substr(\${'$'}2, RSTART, RLENGTH); exit } }")

if [ -z "${'$'}optic_temp" ]; then
  for p in "${'$'}net_path"/device/hwmon/hwmon*/temp1_input; do
    if [ -r "${'$'}p" ]; then
      v=$(cat "${'$'}p" 2>/dev/null | tr -d "\r ")
      case "${'$'}v" in ''|*[!0-9-]*) ;; *)
        if [ "${'$'}v" -gt 0 ] 2>/dev/null; then
          optic_temp=$(awk -v val="${'$'}v" "BEGIN { printf \"%.1f\", val / 1000.0 }")
          break
        fi
      ;; esac
    fi
  done
fi

c0=""; c1=""; ploam=""
has_stick_ip=0
other_stick_ip=0
ip_out=$(ip -4 -o addr show 2>/dev/null || true)
if printf "%s\n" "${'$'}ip_out" | grep -q " ${'$'}IFACE .*192\.168\.1\.2/"; then
  has_stick_ip=1
elif printf "%s\n" "${'$'}ip_out" | grep -q "192\.168\.1\.2/"; then
  other_stick_ip=1
fi

if [ "${'$'}has_stick_ip" = "0" ] && [ "${'$'}other_stick_ip" = "0" ] && [ "${'$'}AUTO_PON" = "1" ]; then
  if [ -n "${'$'}optic_temp" ] || [ "${'$'}carrier" = "1" ] || [ "${'$'}operstate" = "up" ]; then
    ip addr add 192.168.1.2/24 dev "${'$'}IFACE" >/dev/null 2>&1 && has_stick_ip=1
  fi
fi

if [ "${'$'}has_stick_ip" = "1" ]; then
  if ping -I "${'$'}IFACE" -c 1 -W 1 192.168.1.1 >/dev/null 2>&1; then
    stick_out=$(ssh -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o ConnectTimeout=2 -b 192.168.1.2 root@192.168.1.1 "cat /sys/class/thermal/thermal_zone0/temp; cat /sys/class/thermal/thermal_zone1/temp; pon psg" 2>/dev/null || true)
    l1=$(printf "%s\n" "${'$'}stick_out" | sed -n "1p" | tr -d "\r ")
    l2=$(printf "%s\n" "${'$'}stick_out" | sed -n "2p" | tr -d "\r ")
    case "${'$'}l1" in ''|*[!0-9]*) ;; *) c0=$(awk -v val="${'$'}l1" "BEGIN { printf \"%.1f\", val / 1000.0 }") ;; esac
    case "${'$'}l2" in ''|*[!0-9]*) ;; *) c1=$(awk -v val="${'$'}l2" "BEGIN { printf \"%.1f\", val / 1000.0 }") ;; esac
    case "${'$'}stick_out" in
      *current=51*) ploam="O5.1" ;;
      *current=12*) ploam="O1.2" ;;
      *) [ -n "${'$'}c0" ] && ploam="O5.1" ;;
    esac
  fi
fi

if [ -z "${'$'}optic_temp" ] && [ -z "${'$'}c0" ]; then
  if [ "${'$'}carrier" != "1" ] && [ "${'$'}operstate" != "up" ]; then
    kill "${'$'}ping_pid" >/dev/null 2>&1 || true
    rm -f "${'$'}ping_tmp"
    echo "NO_MODULE|${'$'}IFACE|${'$'}operstate"
    exit 0
  fi
fi

if [ -z "${'$'}c0" ]; then
  for tz in /sys/class/thermal/thermal_zone*/temp /sys/class/hwmon/hwmon*/temp*_input; do
    if [ -r "${'$'}tz" ]; then
      val=$(cat "${'$'}tz" 2>/dev/null | tr -d "\r ")
      case "${'$'}val" in ''|*[!0-9-]*) ;; *)
        if [ "${'$'}val" -ge 10000 ] 2>/dev/null && [ "${'$'}val" -le 125000 ] 2>/dev/null; then
          t_fmt=$(awk -v v="${'$'}val" "BEGIN { printf \"%.1f\", v / 1000.0 }")
          if [ -z "${'$'}c0" ]; then
            c0="${'$'}t_fmt"
          elif [ -z "${'$'}c1" ]; then
            c1="${'$'}t_fmt"
            break
          fi
        fi
      ;; esac
    fi
  done
  [ -n "${'$'}c0" ] && [ -z "${'$'}c1" ] && c1="${'$'}c0"
fi

[ -z "${'$'}optic_temp" ] && [ -n "${'$'}c0" ] && optic_temp="${'$'}c0"
if [ -z "${'$'}ploam" ]; then
  if [ "${'$'}carrier" = "1" ] || [ "${'$'}operstate" = "up" ]; then
    ploam="LINK UP"
  else
    ploam="STANDBY"
  fi
fi

wait "${'$'}ping_pid" 2>/dev/null || true
ping_val=$(awk -F"=" "/avg/ { split(\${'$'}2, a, \"/\"); gsub(/[^0-9.]/, \"\", a[2]); if (a[2] != \"\") { print a[2]; exit } }" "${'$'}ping_tmp" 2>/dev/null)
rm -f "${'$'}ping_tmp"
[ -z "${'$'}ping_val" ] && ping_val="0.0"

echo "${'$'}optic_temp|${'$'}c0|${'$'}c1|${'$'}ploam|${'$'}rx_pwr|${'$'}tx_pwr|${'$'}bias|${'$'}ping_val"
'"""
    }

    /**
     * Pure POSIX `/bin/sh` + `awk` port discovery script (no `python3` needed).
     */
    internal fun buildDiscoveryScript(): String {
        return """sh -c '
for p in /sys/class/net/*; do
  [ -e "${'$'}p" ] || continue
  iface=${'$'}{p##*/}
  case "${'$'}iface" in
    lo|veth*|br*|docker*|tun*|wg*|sit*|ip6*|gre*|erspan*|ifb*|teql*|wlan*|ra*|ap*) continue ;;
    eth*|sfp*|en*|swp*|xe*|ten*|opt*|lan*|wan*) ;;
    *) continue ;;
  esac
  state=$(cat "${'$'}p/operstate" 2>/dev/null || echo "unknown")
  out=$(ethtool -m "${'$'}iface" 2>/dev/null || true)
  temp=$(printf "%s\n" "${'$'}out" | awk -F":" "/Module temperature/ { if (match(\${'$'}2, /-?[0-9]+(\.[0-9]+)?/)) { print substr(\${'$'}2, RSTART, RLENGTH); exit } }")
  has_dom="0"
  case "${'$'}out" in
    *"Module temperature"*|*"Laser"*|*"Transceiver"*|*"Identifier"*) has_dom="1" ;;
  esac
  echo "PORT|${'$'}iface|${'$'}state|${'$'}has_dom|${'$'}temp"
done
'"""
    }

    private class SshUserInfo(private val pass: String) : UserInfo, UIKeyboardInteractive {
        override fun getPassphrase(): String = ""
        override fun getPassword(): String = pass
        override fun promptPassword(message: String?): Boolean = pass.isNotEmpty()
        override fun promptPassphrase(message: String?): Boolean = false
        override fun promptYesNo(message: String?): Boolean {
            // Strictly reject changed host keys (MITM protection)
            if (message != null && (
                    message.contains("HostKey has been changed", ignoreCase = true) ||
                    message.contains("REMOTE HOST IDENTIFICATION HAS CHANGED", ignoreCase = true) ||
                    message.contains("offending key", ignoreCase = true) ||
                    message.contains("does not match", ignoreCase = true)
                )
            ) {
                return false
            }
            // Trust On First Use (TOFU): persist unknown host key into owner-only ssh_known_hosts
            return message != null && message.contains("authenticity of host", ignoreCase = true)
        }
        override fun showMessage(message: String?) {}
        override fun promptKeyboardInteractive(
            destination: String?,
            name: String?,
            instruction: String?,
            prompt: Array<out String>?,
            echo: BooleanArray?
        ): Array<String> {
            if (prompt != null && prompt.isNotEmpty() && pass.isNotEmpty()) {
                return Array(prompt.size) { pass }
            }
            return emptyArray()
        }
    }

    /**
     * Fail-closed known_hosts configuration: creates owner-only `ssh_known_hosts` and enforces `ask` (TOFU + strict pin).
     * Never falls back to `StrictHostKeyChecking=no`.
     */
    private fun configureKnownHostsStrict(context: Context, jsch: JSch, session: Session) {
        val knownHostsFile = File(context.filesDir, KNOWN_HOSTS_FILENAME)
        if (!knownHostsFile.exists()) {
            if (!knownHostsFile.createNewFile()) {
                throw SecurityException("Unable to initialize SSH known_hosts store")
            }
        }
        knownHostsFile.setReadable(false, false)
        knownHostsFile.setReadable(true, true)
        knownHostsFile.setWritable(false, false)
        knownHostsFile.setWritable(true, true)

        jsch.setKnownHosts(knownHostsFile.absolutePath)
        session.setConfig("StrictHostKeyChecking", "ask")
        session.setConfig("HashKnownHosts", "no")
    }

    fun getKnownHostsSummary(context: Context): String {
        val file = File(context.filesDir, KNOWN_HOSTS_FILENAME)
        if (!file.exists()) return "No pinned SSH host keys yet (TOFU active)"
        val entries = try {
            file.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        } catch (_: Exception) {
            emptyList()
        }
        if (entries.isEmpty()) return "No pinned SSH host keys yet (TOFU active)"
        val jsch = JSch()
        return try {
            jsch.setKnownHosts(file.absolutePath)
            val hkRepo = jsch.hostKeyRepository
            val keys: Array<HostKey> = hkRepo.hostKey ?: emptyArray()
            if (keys.isEmpty()) {
                "${entries.size} pinned SSH host key(s)"
            } else {
                val first = keys.first()
                "${keys.size} pinned host(s) • ${first.host} (${first.type} ${first.getFingerPrint(jsch)})"
            }
        } catch (_: Exception) {
            "${entries.size} pinned SSH host key(s)"
        }
    }

    fun clearKnownHosts(context: Context): Boolean {
        closePooledSession()
        val file = File(context.filesDir, KNOWN_HOSTS_FILENAME)
        return try {
            if (file.exists()) file.writeText("", Charsets.UTF_8)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun computeSessionAuthKey(host: String, port: Int, user: String, password: String, privateKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("$user@$host:$port|".toByteArray(Charsets.UTF_8))
        digest.update(password.toByteArray(Charsets.UTF_8))
        digest.update("|".toByteArray(Charsets.UTF_8))
        digest.update(privateKey.toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun createConnectedSession(
        context: Context,
        host: String,
        port: Int,
        user: String,
        password: String,
        privateKey: String,
        connectTimeoutMs: Int
    ): Session {
        val jsch = JSch()
        val keyToUse = privateKey.trim()
        if (keyToUse.isNotBlank()) {
            val keyBytes = keyToUse.toByteArray(Charsets.UTF_8)
            try {
                jsch.addIdentity("user_key", keyBytes, null, null)
            } finally {
                keyBytes.fill(0)
            }
        }

        val session = jsch.getSession(user, host, port)
        session.userInfo = SshUserInfo(password)
        if (password.isNotBlank()) {
            val passBytes = password.toByteArray(Charsets.UTF_8)
            try {
                session.setPassword(passBytes)
            } finally {
                passBytes.fill(0)
            }
        }

        configureKnownHostsStrict(context, jsch, session)
        session.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
        session.timeout = connectTimeoutMs
        session.setServerAliveInterval(30_000)
        session.setServerAliveCountMax(2)
        session.connect(connectTimeoutMs)
        return session
    }

    /**
     * Executes a remote command over a pooled SSH session (reconnecting once transparently if stale).
     */
    private fun executeRemoteCommand(
        context: Context,
        host: String,
        port: Int,
        user: String,
        password: String,
        privateKey: String,
        command: String,
        timeoutMs: Int,
        keepPooled: Boolean
    ): Pair<String, String> {
        if (!keepPooled) {
            var oneshotSession: Session? = null
            var channel: ChannelExec? = null
            try {
                oneshotSession = createConnectedSession(context, host, port, user, password, privateKey, timeoutMs)
                channel = (oneshotSession.openChannel("exec") as ChannelExec).apply {
                    setCommand(command)
                    inputStream = null
                }
                val errStream = ByteArrayOutputStream()
                channel.setErrStream(errStream)
                val stdoutStream = channel.inputStream
                channel.connect(timeoutMs)
                val stdout = BufferedReader(InputStreamReader(stdoutStream, Charsets.UTF_8)).use { it.readText().trim() }
                val stderr = errStream.toString(Charsets.UTF_8.name()).trim()
                return stdout to stderr
            } finally {
                try { channel?.disconnect() } catch (_: Exception) {}
                try { oneshotSession?.disconnect() } catch (_: Exception) {}
            }
        }

        val authKey = computeSessionAuthKey(host, port, user, password, privateKey)
        synchronized(sessionLock) {
            for (attempt in 0..1) {
                var channel: ChannelExec? = null
                try {
                    val active = pooledSession
                    val session = if (active != null && active.isConnected && pooledSessionKey == authKey) {
                        active
                    } else {
                        try { active?.disconnect() } catch (_: Exception) {}
                        val fresh = createConnectedSession(context, host, port, user, password, privateKey, timeoutMs)
                        pooledSession = fresh
                        pooledSessionKey = authKey
                        fresh
                    }

                    channel = (session.openChannel("exec") as ChannelExec).apply {
                        setCommand(command)
                        inputStream = null
                    }
                    val errStream = ByteArrayOutputStream()
                    channel.setErrStream(errStream)
                    val stdoutStream = channel.inputStream
                    channel.connect(timeoutMs)
                    val stdout = BufferedReader(InputStreamReader(stdoutStream, Charsets.UTF_8)).use { it.readText().trim() }
                    val stderr = errStream.toString(Charsets.UTF_8.name()).trim()
                    return stdout to stderr
                } catch (e: Exception) {
                    try { pooledSession?.disconnect() } catch (_: Exception) {}
                    pooledSession = null
                    pooledSessionKey = null
                    if (attempt == 1 || (e.message?.contains("HostKey", ignoreCase = true) == true)) {
                        throw e
                    }
                } finally {
                    try { channel?.disconnect() } catch (_: Exception) {}
                }
            }
            throw IllegalStateException("SSH session execution failed")
        }
    }

    /**
     * Closes any pooled foreground SSH session (invoked on Activity stop or credential changes).
     */
    fun closePooledSession() {
        synchronized(sessionLock) {
            try {
                pooledSession?.disconnect()
            } catch (_: Exception) {
            } finally {
                pooledSession = null
                pooledSessionKey = null
            }
        }
    }

    fun collectViaSsh(context: Context, usePool: Boolean = true): Result<TelemetrySample> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val host = prefs.getString(KEY_GATEWAY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
        val port = prefs.getInt(KEY_GATEWAY_PORT, DEFAULT_PORT)
        val user = prefs.getString(KEY_GATEWAY_USER, DEFAULT_USER) ?: DEFAULT_USER
        val password = CryptoHelper.decrypt(prefs.getString(KEY_GATEWAY_PASSWORD, ""))
        val privateKey = CryptoHelper.decrypt(prefs.getString(KEY_GATEWAY_SSH_KEY, "") ?: "")
        val pingTarget = prefs.getString(KEY_PING_TARGET, DEFAULT_PING_TARGET) ?: DEFAULT_PING_TARGET
        val sfpIface = prefs.getString(KEY_SFP_INTERFACE, DEFAULT_SFP_INTERFACE) ?: DEFAULT_SFP_INTERFACE
        val allowPonAutoIp = prefs.getBoolean(KEY_PON_STICK_AUTO_IP, false)

        return try {
            val command = buildRemoteScript(pingTarget, sfpIface, allowPonAutoIp)
            val (raw, stderrText) = executeRemoteCommand(
                context = context,
                host = host,
                port = port,
                user = user,
                password = password,
                privateKey = privateKey,
                command = command,
                timeoutMs = 10_000,
                keepPooled = usePool
            )
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
        }
    }

    fun discoverPorts(context: Context): Result<List<DiscoveredPort>> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val host = prefs.getString(KEY_GATEWAY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
        val port = prefs.getInt(KEY_GATEWAY_PORT, DEFAULT_PORT)
        val user = prefs.getString(KEY_GATEWAY_USER, DEFAULT_USER) ?: DEFAULT_USER
        val password = CryptoHelper.decrypt(prefs.getString(KEY_GATEWAY_PASSWORD, ""))
        val privateKey = CryptoHelper.decrypt(prefs.getString(KEY_GATEWAY_SSH_KEY, "") ?: "")

        return try {
            val (raw, _) = executeRemoteCommand(
                context = context,
                host = host,
                port = port,
                user = user,
                password = password,
                privateKey = privateKey,
                command = buildDiscoveryScript(),
                timeoutMs = 8_000,
                keepPooled = true
            )
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
            val sorted = list.sortedWith(
                compareByDescending<DiscoveredPort> { it.hasOpticDom }
                    .thenByDescending { it.operState.equals("up", ignoreCase = true) }
                    .thenBy { it.iface }
            )
            Result.success(sorted)
        } catch (e: Exception) {
            ErrorLogManager.logError("Port Scan Failed ($user@$host:$port)", e)
            Result.failure(e)
        }
    }

    fun testConnection(context: Context): Result<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val host = prefs.getString(KEY_GATEWAY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
        val port = prefs.getInt(KEY_GATEWAY_PORT, DEFAULT_PORT)
        val user = prefs.getString(KEY_GATEWAY_USER, DEFAULT_USER) ?: DEFAULT_USER
        val password = CryptoHelper.decrypt(prefs.getString(KEY_GATEWAY_PASSWORD, ""))
        val privateKey = CryptoHelper.decrypt(prefs.getString(KEY_GATEWAY_SSH_KEY, "") ?: "")

        return try {
            closePooledSession()
            executeRemoteCommand(
                context = context,
                host = host,
                port = port,
                user = user,
                password = password,
                privateKey = privateKey,
                command = "echo HOTBOI_OK",
                timeoutMs = 7_000,
                keepPooled = true
            )
            Result.success("Connection Successful! Authenticated with $user@$host:$port")
        } catch (e: Exception) {
            ErrorLogManager.logError("Test Connection Failed ($user@$host:$port)", e)
            Result.failure(e)
        }
    }
}
