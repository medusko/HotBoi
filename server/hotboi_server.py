#!/usr/bin/env python3
"""
HotBoi Thermal Monitor & Android Companion Server
- Collects telemetry from Gateway and optional XGS-PON SFP+ Stick
- Serves responsive mobile dashboard and REST API on port 8080 (localhost by default)
- Supports --bind, --api-token (HOTBOI_API_TOKEN), and opt-in --pon-stick-subnet
"""

import argparse
import hmac
import http.server
import json
import os
import socketserver
import subprocess
import threading
import time
from datetime import datetime

PORT = 8080
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_DIR = os.path.dirname(BASE_DIR)
SCRATCH_DIR = BASE_DIR
CSV_PATH = os.path.join(SCRATCH_DIR, "thermal_log.csv")
HTML_PATH = os.path.join(PROJECT_DIR, "app/src/main/assets/dashboard.html")
APK_PATH = os.path.join(PROJECT_DIR, "app/build/outputs/apk/debug/app-debug.apk")
CHART_PATH = os.path.join(PROJECT_DIR, "app/src/main/assets/chart.min.js")

os.makedirs(SCRATCH_DIR, exist_ok=True)
telemetry_lock = threading.Lock()
configured_api_token = os.environ.get("HOTBOI_API_TOKEN", "").strip()
allow_pon_subnet = False

last_known = {
    "timestamp": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
    "optic_temp": "43.08",
    "cpu0_temp": "55.8",
    "cpu1_temp": "54.5",
    "ploam": "O5.1",
    "rx_power": "-22.15",
    "tx_power": "4.52",
    "bias_ma": "15.5",
    "ping_ms": "8.385"
}

if os.path.exists(CSV_PATH):
    try:
        with open(CSV_PATH, "r", encoding="utf-8") as f:
            lines = [l.strip() for l in f if l.strip()]
            for l in reversed(lines[1:]):
                parts = l.split(",")
                if len(parts) >= 9 and parts[2] not in ("0", "0.0", ""):
                    last_known["timestamp"] = parts[0]
                    last_known["optic_temp"] = parts[1]
                    last_known["cpu0_temp"] = parts[2]
                    last_known["cpu1_temp"] = parts[3]
                    last_known["ploam"] = parts[4]
                    last_known["rx_power"] = parts[5]
                    last_known["tx_power"] = parts[6]
                    last_known["bias_ma"] = parts[7]
                    last_known["ping_ms"] = parts[8]
                    break
    except Exception as e:
        print(f"Error seeding from CSV: {e}")


def collect_sample():
    global last_known
    ts = datetime.now().strftime("%Y-%m-%d %H:%M:%S")

    ping_ms = last_known["ping_ms"]
    try:
        p = subprocess.run(["ping", "-c", "1", "-W", "1000", "1.1.1.1"], capture_output=True, text=True, timeout=3)
        for line in p.stdout.splitlines():
            if "avg" in line and "/" in line:
                ping_ms = line.split("=")[-1].strip().split("/")[1]
    except Exception:
        pass

    auto_pon_flag = "1" if allow_pon_subnet else "0"
    remote_script = f"""sh -c '
AUTO_PON="{auto_pon_flag}"
eth_out=$(ethtool -m eth6 2>/dev/null || true)
optic_temp=$(printf "%s\\n" "$eth_out" | awk -F":" "/Module temperature/ {{ if (match(\\$2, /-?[0-9]+(\\.[0-9]+)?/)) {{ print substr(\\$2, RSTART, RLENGTH); exit }} }}")
rx_pwr=$(printf "%s\\n" "$eth_out" | awk -F"/" "/(Receiver signal average optical power|Rx power)[[:space:]]*:/ {{ if (match(\\$2, /-?[0-9]+(\\.[0-9]+)?/)) {{ print substr(\\$2, RSTART, RLENGTH); exit }} }}")
tx_pwr=$(printf "%s\\n" "$eth_out" | awk -F"/" "/(Laser output power|Laser transmit average optical power)[[:space:]]*:/ {{ if (match(\\$2, /-?[0-9]+(\\.[0-9]+)?/)) {{ print substr(\\$2, RSTART, RLENGTH); exit }} }}")
bias=$(printf "%s\\n" "$eth_out" | awk -F":" "/Laser bias current/ {{ if (match(\\$2, /[0-9]+(\\.[0-9]+)?/)) {{ print substr(\\$2, RSTART, RLENGTH); exit }} }}")

has_stick_ip=0
ip_out=$(ip -4 -o addr show dev eth6 2>/dev/null || true)
if printf "%s\\n" "$ip_out" | grep -q "192\\.168\\.1\\.2/"; then
  has_stick_ip=1
elif [ "$AUTO_PON" = "1" ]; then
  ip addr add 192.168.1.2/24 dev eth6 >/dev/null 2>&1 && has_stick_ip=1
fi

c0=""; c1=""; ploam=""
if [ "$has_stick_ip" = "1" ]; then
  stick_out=$(ssh -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o ConnectTimeout=2 root@192.168.1.1 "cat /sys/class/thermal/thermal_zone0/temp; cat /sys/class/thermal/thermal_zone1/temp; pon psg" 2>/dev/null || true)
  l1=$(printf "%s\\n" "$stick_out" | sed -n "1p" | tr -d "\\r ")
  l2=$(printf "%s\\n" "$stick_out" | sed -n "2p" | tr -d "\\r ")
  case "$l1" in ""|*[!0-9]*) ;; *) c0=$(awk -v val="$l1" "BEGIN {{ printf \\"%.1f\\", val / 1000.0 }}") ;; esac
  case "$l2" in ""|*[!0-9]*) ;; *) c1=$(awk -v val="$l2" "BEGIN {{ printf \\"%.1f\\", val / 1000.0 }}") ;; esac
  case "$stick_out" in *current=51*) ploam="O5.1" ;; *current=12*) ploam="O1.2" ;; *) [ -n "$c0" ] && ploam="O5.1" ;; esac
fi
echo "$optic_temp|$c0|$c1|$ploam|$rx_pwr|$tx_pwr|$bias"
'"""
    try:
        res = subprocess.run(
            ["ssh", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=accept-new", "-o", "ConnectTimeout=4", "--", "root@192.168.0.254", remote_script],
            capture_output=True, text=True, timeout=8
        )
        raw_out = res.stdout.strip()
        data_lines = [ln.strip() for ln in raw_out.splitlines() if "|" in ln]
        last_line = data_lines[-1] if data_lines else raw_out
        parts = last_line.split("|")
        if len(parts) == 7:
            optic_t, c0, c1, ploam, rx, tx, bias = parts
            with telemetry_lock:
                if optic_t:
                    last_known["optic_temp"] = optic_t
                if c0 and c0 not in ("0", "0.0"):
                    last_known["cpu0_temp"] = c0
                    last_known["cpu1_temp"] = c1
                if ploam:
                    last_known["ploam"] = ploam
                if rx:
                    last_known["rx_power"] = rx
                if tx:
                    last_known["tx_power"] = tx
                if bias:
                    last_known["bias_ma"] = bias
                last_known["ping_ms"] = ping_ms
                last_known["timestamp"] = ts
                return dict(last_known)
    except Exception as e:
        print(f"[{ts}] Poll note: {e}")
    return None


def background_poller():
    while True:
        sample = collect_sample()
        if sample:
            with open(CSV_PATH, "a", encoding="utf-8") as f:
                f.write(
                    f"{sample['timestamp']},{sample['optic_temp']},{sample['cpu0_temp']},{sample['cpu1_temp']},"
                    f"{sample['ploam']},{sample['rx_power']},{sample['tx_power']},{sample['bias_ma']},{sample['ping_ms']}\n"
                )
            print(f"[{sample['timestamp']}] Logged: Optic={sample['optic_temp']}C, CPU0={sample['cpu0_temp']}C, PLOAM={sample['ploam']}, Ping={sample['ping_ms']}ms", flush=True)
        time.sleep(300)


class HotBoiHTTPHandler(http.server.BaseHTTPRequestHandler):
    timeout = 10

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

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        if self.path in ("/", "/index.html"):
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            if os.path.exists(HTML_PATH):
                with open(HTML_PATH, "rb") as f:
                    self.wfile.write(f.read())
            else:
                self.wfile.write(b"<h1>HotBoi Dashboard Loading...</h1>")

        elif self.path == "/chart.min.js":
            self.send_response(200)
            self.send_header("Content-Type", "application/javascript")
            self.end_headers()
            if os.path.exists(CHART_PATH):
                with open(CHART_PATH, "rb") as f:
                    self.wfile.write(f.read())

        elif self.path in ("/api/telemetry", "/api/data"):
            if not self._is_authorized():
                self.send_response(401)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(b'{"error":"Unauthorized"}')
                return
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            with telemetry_lock:
                payload = json.dumps(last_known).encode("utf-8")
            self.wfile.write(payload)

        elif self.path in ("/hotboi.apk", "/app.apk"):
            if not self._is_authorized():
                self.send_response(401)
                self.end_headers()
                return
            if os.path.exists(APK_PATH):
                self.send_response(200)
                self.send_header("Content-Type", "application/vnd.android.package-archive")
                self.send_header("Content-Disposition", 'attachment; filename="HotBoi.apk"')
                self.send_header("Content-Length", str(os.path.getsize(APK_PATH)))
                self.end_headers()
                with open(APK_PATH, "rb") as f:
                    while chunk := f.read(65536):
                        self.wfile.write(chunk)
            else:
                self.send_response(404)
                self.end_headers()
                self.wfile.write(b"APK not found. Build it with ./gradlew assembleDebug")
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, format, *args):
        return


class HotBoiThreadingTCPServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def main():
    global configured_api_token, allow_pon_subnet
    parser = argparse.ArgumentParser(description="HotBoi Thermal Companion Server")
    parser.add_argument("--bind", default="127.0.0.1", help="Bind address (default: 127.0.0.1; pass 0.0.0.0 for LAN)")
    parser.add_argument("--port", type=int, default=PORT, help="HTTP port (default: 8080)")
    parser.add_argument("--api-token", default="", help="Optional API token (or set HOTBOI_API_TOKEN)")
    parser.add_argument("--pon-stick-subnet", action="store_true", help="Opt-in: auto-assign 192.168.1.2/24 on eth6")
    args = parser.parse_args()

    if args.api_token.strip():
        configured_api_token = args.api_token.strip()
    allow_pon_subnet = bool(args.pon_stick_subnet)

    print("=" * 60)
    print(f"HotBoi Thermal Server bound to http://{args.bind}:{args.port}")
    if args.bind == "127.0.0.1":
        print("Bound to localhost only. Pass --bind 0.0.0.0 to allow LAN connections.")
    print("=" * 60, flush=True)

    t = threading.Thread(target=background_poller, daemon=True)
    t.start()

    with HotBoiThreadingTCPServer((args.bind, args.port), HotBoiHTTPHandler) as httpd:
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\nShutting down HotBoi server.")


if __name__ == "__main__":
    main()
