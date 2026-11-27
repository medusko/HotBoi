#!/usr/bin/env python3
"""
HotBoi Telemetry Collector & Local Web Server
Cross-platform: macOS, Windows 10/11, and Linux.

Collects telemetry from Gateway SFP+ optical module, logs to CSV & JSON,
and serves a local HTTP dashboard & API on port 8080.

Security defaults:
- Binds to 127.0.0.1 by default (pass --bind 0.0.0.0 for LAN mobile access).
- Enforces StrictHostKeyChecking=accept-new (TOFU) instead of disabling SSH host verification.
- Never assigns 192.168.1.2/24 on the gateway interface unless --pon-stick-subnet is explicitly passed.
- Supports --api-token (or HOTBOI_API_TOKEN env var) via X-HotBoi-Token / Bearer header and drops wildcard CORS on mutations.
"""

import os
import re
import hmac
import time
import json
import base64
import argparse
import subprocess
import threading
from collections import deque
from datetime import datetime
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

# Default Settings
DEFAULT_GATEWAY = "192.168.0.254"
DEFAULT_USER = "root"
DEFAULT_PORT = 22
DEFAULT_SFP_IFACE = "eth6"
DEFAULT_PING_TARGET = "1.1.1.1"
DEFAULT_INTERVAL_SEC = 60
DEFAULT_BIND_HOST = "127.0.0.1"
DEFAULT_HTTP_PORT = 8080
MAX_HISTORY_POINTS = 2500
CSV_COMPACT_THRESHOLD = 5000
MAX_POST_BODY_BYTES = 4096

SAFE_HOST_RE = re.compile(r"^[a-zA-Z0-9][a-zA-Z0-9.:-]{0,252}$")
SAFE_IFACE_RE = re.compile(r"^[a-zA-Z0-9][a-zA-Z0-9._-]{0,31}$")
SAFE_USER_RE = re.compile(r"^[a-zA-Z0-9_][a-zA-Z0-9._-]{0,31}$")

SCRIPT_DIR = Path(__file__).parent.resolve()
CSV_FILE = SCRIPT_DIR / "telemetry_history.csv"
JSON_FILE = SCRIPT_DIR / "telemetry_history.json"
DASHBOARD_FILE = SCRIPT_DIR / "dashboard.html"
CHART_FILE = SCRIPT_DIR / "chart.min.js"

CSV_HEADER = "timestamp,optic_temp,cpu0_temp,cpu1_temp,ploam,rx_power,tx_power,bias_ma,ping_ms\n"

# In-memory latest sample cache, bounded history deque & dynamic configuration
current_ping_target = DEFAULT_PING_TARGET
current_poll_interval = DEFAULT_INTERVAL_SEC
configured_api_token = os.environ.get("HOTBOI_API_TOKEN", "").strip()
current_telemetry = {
    "timestamp": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
    "optic_temp": 42.0,
    "cpu0_temp": 55.0,
    "cpu1_temp": 55.0,
    "ploam": "O5.1",
    "rx_power": -22.0,
    "tx_power": 4.5,
    "bias_ma": 15.0,
    "ping_ms": 7.5,
    "ping_target": current_ping_target,
    "device_name": "Primary Network Gateway",
    "sfp_port": "Port 7 SFP+"
}

telemetry_lock = threading.Lock()
history_samples = deque(maxlen=MAX_HISTORY_POINTS)
cached_history_bytes = None
csv_line_count = 0


def get_default_ssh_key():
    home = Path.home()
    for name in ["id_ed25519", "id_rsa"]:
        candidate = home / ".ssh" / name
        if candidate.is_file():
            return str(candidate)
    return None


def validate_host(value: str, fallback: str = DEFAULT_PING_TARGET) -> str:
    val = (value or "").strip()
    return val if SAFE_HOST_RE.match(val) else fallback


def validate_iface(value: str, fallback: str = DEFAULT_SFP_IFACE) -> str:
    val = (value or "").strip()
    return val if SAFE_IFACE_RE.match(val) else fallback


def validate_user(value: str, fallback: str = DEFAULT_USER) -> str:
    val = (value or "").strip()
    return val if SAFE_USER_RE.match(val) else fallback


def init_history_from_csv():
    global csv_line_count, cached_history_bytes
    loaded = []
    total_lines = 0
    if CSV_FILE.is_file():
        try:
            with open(CSV_FILE, "r", encoding="utf-8") as f:
                lines = f.readlines()
                total_lines = len(lines)
                if len(lines) > 1:
                    data_lines = lines[1:][-MAX_HISTORY_POINTS:]
                    for line in data_lines:
                        parts = [p.strip() for p in line.split(",")]
                        if len(parts) >= 9:
                            try:
                                s = {
                                    "timestamp": parts[0],
                                    "optic_temp": float(parts[1]) if parts[1] else 0.0,
                                    "cpu0_temp": float(parts[2]) if parts[2] else 0.0,
                                    "cpu1_temp": float(parts[3]) if parts[3] else 0.0,
                                    "ploam": parts[4],
                                    "rx_power": float(parts[5]) if parts[5] else 0.0,
                                    "tx_power": float(parts[6]) if parts[6] else 0.0,
                                    "bias_ma": float(parts[7]) if parts[7] else 0.0,
                                    "ping_ms": float(parts[8]) if parts[8] else 0.0
                                }
                                loaded.append(s)
                            except ValueError:
                                pass
        except Exception as e:
            print(f"[!] Error reading CSV history: {e}", flush=True)

    with telemetry_lock:
        history_samples.clear()
        history_samples.extend(loaded)
        csv_line_count = total_lines
        if loaded:
            current_telemetry.update(loaded[-1])
        cached_history_bytes = None


def query_gateway_ssh(
    host: str,
    port: int,
    user: str,
    key_path: str,
    sfp_iface: str,
    ping_target: str,
    allow_pon_subnet: bool = False
) -> dict:
    safe_host = validate_host(host, DEFAULT_GATEWAY)
    safe_user = validate_user(user, DEFAULT_USER)
    safe_iface = validate_iface(sfp_iface, DEFAULT_SFP_IFACE)
    safe_target = validate_host(ping_target, DEFAULT_PING_TARGET)
    auto_pon_flag = "1" if allow_pon_subnet else "0"

    # Pure POSIX shell probe matching the Android Direct SSH engine (no python3 needed on gateway)
    remote_command = f"""sh -c '
IFACE="{safe_iface}"
TARGET="{safe_target}"
AUTO_PON="{auto_pon_flag}"
ping_tmp="/tmp/.hb_ping_$$"
(ping -c 1 -W 1 "$TARGET" > "$ping_tmp" 2>/dev/null) &
ping_pid=$!

net_path="/sys/class/net/$IFACE"
operstate=$(cat "$net_path/operstate" 2>/dev/null || echo "unknown")
carrier=$(cat "$net_path/carrier" 2>/dev/null || echo "0")

eth_out=$(ethtool -m "$IFACE" 2>/dev/null || true)
optic_temp=$(printf "%s\\n" "$eth_out" | awk -F":" "/Module temperature/ {{ if (match(\\$2, /-?[0-9]+(\\.[0-9]+)?/)) {{ print substr(\\$2, RSTART, RLENGTH); exit }} }}")
rx_pwr=$(printf "%s\\n" "$eth_out" | awk -F"/" "/(Receiver signal average optical power|Rx power)[[:space:]]*:/ {{ if (match(\\$2, /-?[0-9]+(\\.[0-9]+)?/)) {{ print substr(\\$2, RSTART, RLENGTH); exit }} }}")
tx_pwr=$(printf "%s\\n" "$eth_out" | awk -F"/" "/(Laser output power|Laser transmit average optical power)[[:space:]]*:/ {{ if (match(\\$2, /-?[0-9]+(\\.[0-9]+)?/)) {{ print substr(\\$2, RSTART, RLENGTH); exit }} }}")
bias=$(printf "%s\\n" "$eth_out" | awk -F":" "/Laser bias current/ {{ if (match(\\$2, /[0-9]+(\\.[0-9]+)?/)) {{ print substr(\\$2, RSTART, RLENGTH); exit }} }}")

c0=""; c1=""; ploam=""
has_stick_ip=0
ip_out=$(ip -4 -o addr show 2>/dev/null || true)
if printf "%s\\n" "$ip_out" | grep -q " $IFACE .*192\\.168\\.1\\.2/"; then
  has_stick_ip=1
elif [ "$AUTO_PON" = "1" ] && ! printf "%s\\n" "$ip_out" | grep -q "192\\.168\\.1\\.2/"; then
  ip addr add 192.168.1.2/24 dev "$IFACE" >/dev/null 2>&1 && has_stick_ip=1
fi

if [ "$has_stick_ip" = "1" ] && ping -I "$IFACE" -c 1 -W 1 192.168.1.1 >/dev/null 2>&1; then
  stick_out=$(ssh -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o ConnectTimeout=2 -b 192.168.1.2 root@192.168.1.1 "cat /sys/class/thermal/thermal_zone0/temp; cat /sys/class/thermal/thermal_zone1/temp; pon psg" 2>/dev/null || true)
  l1=$(printf "%s\\n" "$stick_out" | sed -n "1p" | tr -d "\\r ")
  l2=$(printf "%s\\n" "$stick_out" | sed -n "2p" | tr -d "\\r ")
  case "$l1" in ""|*[!0-9]*) ;; *) c0=$(awk -v val="$l1" "BEGIN {{ printf \\"%.1f\\", val / 1000.0 }}") ;; esac
  case "$l2" in ""|*[!0-9]*) ;; *) c1=$(awk -v val="$l2" "BEGIN {{ printf \\"%.1f\\", val / 1000.0 }}") ;; esac
  case "$stick_out" in *current=51*) ploam="O5.1" ;; *current=12*) ploam="O1.2" ;; *) [ -n "$c0" ] && ploam="O5.1" ;; esac
fi

if [ -z "$c0" ]; then
  for tz in /sys/class/thermal/thermal_zone*/temp /sys/class/hwmon/hwmon*/temp*_input; do
    [ -r "$tz" ] || continue
    val=$(cat "$tz" 2>/dev/null | tr -d "\\r ")
    case "$val" in ""|*[!0-9-]*) ;; *)
      if [ "$val" -ge 10000 ] 2>/dev/null && [ "$val" -le 125000 ] 2>/dev/null; then
        t_fmt=$(awk -v v="$val" "BEGIN {{ printf \\"%.1f\\", v / 1000.0 }}")
        if [ -z "$c0" ]; then c0="$t_fmt"; elif [ -z "$c1" ]; then c1="$t_fmt"; break; fi
      fi
    ;; esac
  done
  [ -n "$c0" ] && [ -z "$c1" ] && c1="$c0"
fi

[ -z "$optic_temp" ] && [ -n "$c0" ] && optic_temp="$c0"
[ -z "$ploam" ] && ploam=$([ "$carrier" = "1" ] || [ "$operstate" = "up" ] && echo "LINK UP" || echo "STANDBY")
wait "$ping_pid" 2>/dev/null || true
ping_val=$(awk -F"=" "/avg/ {{ split(\\$2, a, \\"/\\"); gsub(/[^0-9.]/, \\"\\", a[2]); if (a[2] != \\"\\") {{ print a[2]; exit }} }}" "$ping_tmp" 2>/dev/null)
rm -f "$ping_tmp"
[ -z "$ping_val" ] && ping_val="0.0"
echo "$optic_temp|$c0|$c1|$ploam|$rx_pwr|$tx_pwr|$bias|$ping_val"
'"""

    ssh_cmd = [
        "ssh",
        "-o", "BatchMode=yes",
        "-o", "StrictHostKeyChecking=accept-new",
        "-o", "ConnectTimeout=5",
        "-p", str(int(port)),
    ]
    if key_path:
        ssh_cmd.extend(["-i", key_path])
    ssh_cmd.extend(["--", f"{safe_user}@{safe_host}", remote_command])

    proc = subprocess.run(ssh_cmd, capture_output=True, text=True, timeout=12)
    if proc.returncode != 0:
        raise RuntimeError(f"SSH failed ({proc.returncode}): {proc.stderr.strip()}")

    output = proc.stdout.strip()
    data_lines = [ln.strip() for ln in output.splitlines() if "|" in ln]
    last_line = data_lines[-1] if data_lines else (output.splitlines()[-1] if output else "")
    parts = last_line.split("|")
    if len(parts) < 8:
        raise ValueError(f"Malformed output: {output}")

    with telemetry_lock:
        fallback = dict(current_telemetry)

    optic_temp = float(parts[0]) if parts[0] else fallback["optic_temp"]
    c0 = float(parts[1]) if parts[1] else fallback["cpu0_temp"]
    c1 = float(parts[2]) if parts[2] else fallback["cpu1_temp"]
    ploam = parts[3] if parts[3] else fallback["ploam"]
    rx = float(parts[4]) if parts[4] else fallback["rx_power"]
    tx = float(parts[5]) if parts[5] else fallback["tx_power"]
    bias = float(parts[6]) if parts[6] else fallback["bias_ma"]
    ping_val = float(parts[7]) if parts[7] else fallback["ping_ms"]

    return {
        "timestamp": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "optic_temp": optic_temp,
        "cpu0_temp": c0,
        "cpu1_temp": c1,
        "ploam": ploam,
        "rx_power": rx,
        "tx_power": tx,
        "bias_ma": bias,
        "ping_ms": ping_val,
        "device_name": "Primary Network Gateway",
        "sfp_port": f"SFP+ ({safe_iface})"
    }


def record_sample(sample: dict):
    global csv_line_count, cached_history_bytes
    with telemetry_lock:
        current_telemetry.update(sample)
        history_samples.append({
            "timestamp": sample["timestamp"],
            "optic_temp": sample["optic_temp"],
            "cpu0_temp": sample["cpu0_temp"],
            "cpu1_temp": sample["cpu1_temp"],
            "ploam": sample["ploam"],
            "rx_power": sample["rx_power"],
            "tx_power": sample["tx_power"],
            "bias_ma": sample["bias_ma"],
            "ping_ms": sample["ping_ms"]
        })
        cached_history_bytes = None

        try:
            write_header = not CSV_FILE.exists()
            with open(CSV_FILE, "a", encoding="utf-8") as f:
                if write_header:
                    f.write(CSV_HEADER)
                    csv_line_count = 1
                f.write(
                    f"{sample['timestamp']},{sample['optic_temp']},{sample['cpu0_temp']},{sample['cpu1_temp']},"
                    f"{sample['ploam']},{sample['rx_power']},{sample['tx_power']},{sample['bias_ma']},{sample['ping_ms']}\n"
                )
                csv_line_count += 1

            if csv_line_count > CSV_COMPACT_THRESHOLD:
                tmp_csv = CSV_FILE.with_suffix(".csv.tmp")
                with open(tmp_csv, "w", encoding="utf-8") as f:
                    f.write(CSV_HEADER)
                    for s in history_samples:
                        f.write(
                            f"{s['timestamp']},{s['optic_temp']},{s['cpu0_temp']},{s['cpu1_temp']},"
                            f"{s['ploam']},{s['rx_power']},{s['tx_power']},{s['bias_ma']},{s['ping_ms']}\n"
                        )
                os.replace(tmp_csv, CSV_FILE)
                csv_line_count = len(history_samples) + 1
        except Exception as e:
            print(f"[!] Error writing CSV history: {e}", flush=True)

        try:
            tmp_json = JSON_FILE.with_suffix(".json.tmp")
            with open(tmp_json, "w", encoding="utf-8") as f:
                json.dump(sample, f, indent=2)
            os.replace(tmp_json, JSON_FILE)
        except Exception as e:
            print(f"[!] Error writing latest JSON: {e}", flush=True)


def get_history_bundle_bytes() -> bytes:
    global cached_history_bytes
    with telemetry_lock:
        if cached_history_bytes is not None:
            return cached_history_bytes

        latest = dict(current_telemetry)
        samples = list(history_samples) if history_samples else [latest]
        target = current_ping_target

        labels = []
        fullTimestamps = []
        opticData = []
        cpu0Data = []
        cpu1Data = []
        pingData = []

        for s in samples:
            ts = s["timestamp"]
            fullTimestamps.append(ts)
            time_part = ts.split(" ")[1][:5] if " " in ts else ts
            labels.append(time_part)
            opticData.append(s["optic_temp"])
            cpu0Data.append(s["cpu0_temp"])
            cpu1Data.append(s["cpu1_temp"])
            pingData.append(s["ping_ms"])

        bundle = {
            "latest": latest,
            "sampleCount": len(samples),
            "samples": samples,
            "labels": labels,
            "fullTimestamps": fullTimestamps,
            "opticData": opticData,
            "cpu0Data": cpu0Data,
            "cpu1Data": cpu1Data,
            "pingData": pingData,
            "deviceName": latest.get("device_name", "Primary Network Gateway"),
            "portName": latest.get("sfp_port", "Port 7 SFP+"),
            "pingTarget": target,
            "ping_target": target,
            "mode": "server"
        }
        cached_history_bytes = json.dumps(bundle).encode("utf-8")
        return cached_history_bytes


def collection_loop(host, port, user, key_path, sfp_iface, interval, allow_pon_subnet):
    print(f"[*] Starting telemetry polling every {interval}s from {user}@{host}:{port} ({sfp_iface})...", flush=True)
    while True:
        try:
            with telemetry_lock:
                target = current_ping_target
            sample = query_gateway_ssh(host, port, user, key_path, sfp_iface, target, allow_pon_subnet)
            sample["ping_target"] = target
            record_sample(sample)
            print(f"[{sample['timestamp']}] Laser: {sample['optic_temp']}°C | CPU0: {sample['cpu0_temp']}°C | Rx: {sample['rx_power']} dBm | Ping ({target}): {sample['ping_ms']} ms", flush=True)
        except Exception as e:
            print(f"[!] Polling Error: {e}", flush=True)
        time.sleep(interval)


class HotBoiThreadingHTTPServer(ThreadingHTTPServer):
    allow_reuse_address = True
    daemon_threads = True


class HotBoiHttpHandler(BaseHTTPRequestHandler):
    timeout = 10

    def log_message(self, format, *args):
        pass

    def _is_authorized(self) -> bool:
        if not configured_api_token:
            return True
        provided = (self.headers.get("X-HotBoi-Token") or "").strip()
        if not provided:
            auth_hdr = (self.headers.get("Authorization") or "").strip()
            if auth_hdr.lower().startswith("bearer "):
                provided = auth_hdr[7:].strip()
        if not provided:
            return False
        return hmac.compare_digest(provided.encode("utf-8"), configured_api_token.encode("utf-8"))

    def _reject_unauthorized(self):
        self.send_response(401)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(json.dumps({"error": "Unauthorized: valid X-HotBoi-Token required"}).encode("utf-8"))

    def _is_cross_origin_browser_mutation(self) -> bool:
        origin = (self.headers.get("Origin") or "").strip()
        if not origin:
            return False
        host_hdr = (self.headers.get("Host") or "").strip()
        parsed_origin = urlparse(origin)
        return parsed_origin.netloc != host_hdr

    def do_OPTIONS(self):
        # Do not advertise wildcard CORS for cross-origin browser mutations
        self.send_response(204)
        self.send_header("Access-Control-Allow-Methods", "GET, OPTIONS")
        self.end_headers()

    def do_POST(self):
        global current_ping_target, cached_history_bytes
        parsed = urlparse(self.path)
        if parsed.path == "/api/config":
            if self._is_cross_origin_browser_mutation():
                self.send_response(403)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({"error": "Cross-origin POST forbidden"}).encode("utf-8"))
                return

            if not self._is_authorized():
                self._reject_unauthorized()
                return

            try:
                content_length = int(self.headers.get("Content-Length", 0))
            except ValueError:
                content_length = 0

            if content_length <= 0 or content_length > MAX_POST_BODY_BYTES:
                self.send_response(400)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({"error": "Invalid Content-Length"}).encode("utf-8"))
                return

            try:
                body = self.rfile.read(content_length).decode("utf-8")
                data = json.loads(body)
                if "ping_target" in data and data["ping_target"]:
                    candidate = str(data["ping_target"]).strip()
                    if not SAFE_HOST_RE.match(candidate):
                        self.send_response(400)
                        self.send_header("Content-Type", "application/json")
                        self.end_headers()
                        self.wfile.write(json.dumps({"error": "Invalid ping_target format"}).encode("utf-8"))
                        return
                    with telemetry_lock:
                        current_ping_target = candidate
                        current_telemetry["ping_target"] = current_ping_target
                        cached_history_bytes = None
                    print(f"[*] Dynamic config update: ping target set to {current_ping_target}", flush=True)

                with telemetry_lock:
                    resp_target = current_ping_target
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({"status": "ok", "ping_target": resp_target}).encode("utf-8"))
            except Exception as e:
                self.send_response(400)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({"error": str(e)}).encode("utf-8"))
            return

        self.send_response(404)
        self.end_headers()

    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path in ["/api/telemetry", "/telemetry.json", "/api/history", "/history.json", "/api/config"]:
            if not self._is_authorized():
                self._reject_unauthorized()
                return

        if parsed.path in ["/api/telemetry", "/telemetry.json"]:
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            with telemetry_lock:
                data = json.dumps(current_telemetry).encode("utf-8")
            self.wfile.write(data)
            return

        if parsed.path in ["/api/history", "/history.json"]:
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(get_history_bundle_bytes())
            return

        if parsed.path == "/api/config":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            with telemetry_lock:
                cfg = {
                    "status": "ok",
                    "ping_target": current_ping_target,
                    "poll_interval": current_poll_interval,
                    "auth_enabled": bool(configured_api_token)
                }
            self.wfile.write(json.dumps(cfg).encode("utf-8"))
            return

        if parsed.path == "/health":
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.end_headers()
            self.wfile.write(b"OK")
            return

        if parsed.path in ["/", "/index.html", "/dashboard.html"]:
            if DASHBOARD_FILE.is_file():
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.end_headers()
                self.wfile.write(DASHBOARD_FILE.read_bytes())
                return

        if parsed.path == "/chart.min.js":
            if CHART_FILE.is_file():
                self.send_response(200)
                self.send_header("Content-Type", "application/javascript; charset=utf-8")
                self.end_headers()
                self.wfile.write(CHART_FILE.read_bytes())
                return

        self.send_response(404)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.end_headers()
        self.wfile.write(b"Not Found")


def main():
    parser = argparse.ArgumentParser(description="HotBoi SFP+ Telemetry Collector & Web Server")
    parser.add_argument("--host", default=DEFAULT_GATEWAY, help="Gateway IP (default: 192.168.0.254)")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="SSH port (default: 22)")
    parser.add_argument("--user", default=DEFAULT_USER, help="SSH user (default: root)")
    parser.add_argument("--key", default=get_default_ssh_key(), help="SSH private key path (e.g. ~/.ssh/id_ed25519)")
    parser.add_argument("--sfp", default=DEFAULT_SFP_IFACE, help="SFP+ interface (default: eth6)")
    parser.add_argument("--ping", default=DEFAULT_PING_TARGET, help="Ping target (default: 1.1.1.1)")
    parser.add_argument("--interval", type=int, default=DEFAULT_INTERVAL_SEC, help="Poll interval in seconds (default: 60)")
    parser.add_argument("--bind", default=DEFAULT_BIND_HOST, help="HTTP bind address (default: 127.0.0.1; pass 0.0.0.0 for LAN)")
    parser.add_argument("--http-port", type=int, default=DEFAULT_HTTP_PORT, help="HTTP server port (default: 8080)")
    parser.add_argument("--api-token", default="", help="Optional API token (or set HOTBOI_API_TOKEN env var)")
    parser.add_argument("--pon-stick-subnet", action="store_true", help="Opt-in: auto-assign 192.168.1.2/24 on SFP+ interface for PON sticks")
    args = parser.parse_args()

    global current_ping_target, current_poll_interval, configured_api_token
    current_ping_target = validate_host(args.ping, DEFAULT_PING_TARGET)
    current_poll_interval = max(5, int(args.interval))
    if args.api_token.strip():
        configured_api_token = args.api_token.strip()

    init_history_from_csv()
    with telemetry_lock:
        current_telemetry["ping_target"] = current_ping_target

    collector_thread = threading.Thread(
        target=collection_loop,
        args=(
            validate_host(args.host, DEFAULT_GATEWAY),
            args.port,
            validate_user(args.user, DEFAULT_USER),
            args.key,
            validate_iface(args.sfp, DEFAULT_SFP_IFACE),
            current_poll_interval,
            bool(args.pon_stick_subnet)
        ),
        daemon=True
    )
    collector_thread.start()

    server_address = (args.bind, args.http_port)
    httpd = HotBoiThreadingHTTPServer(server_address, HotBoiHttpHandler)
    print(f"[*] HotBoi Web Server bound to http://{args.bind}:{args.http_port}")
    if args.bind == "127.0.0.1":
        print("[*] Bound to localhost only (secure default). Pass --bind 0.0.0.0 to allow LAN phone connections.")
    if configured_api_token:
        print("[*] Token authentication ENABLED (X-HotBoi-Token required on /api/*).")
    print("[*] Press Ctrl+C to stop.", flush=True)

    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping HotBoi Collector...")
        httpd.server_close()


if __name__ == "__main__":
    main()
