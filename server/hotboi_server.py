#!/usr/bin/env python3
"""
HotBoi Thermal Monitor & Android Companion Server
- Collects telemetry from UniFi Gateway (192.168.0.254) and XGS-PON SFP+ Stick (192.168.1.1)
- Serves responsive mobile dashboard and REST API on port 8080
- Serves the HotBoi Android APK directly at /hotboi.apk for one-tap install on your phone
"""

import http.server
import socketserver
import subprocess
import threading
import time
import os
import re
import json
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

# Global last known good telemetry
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

# Seed last known from CSV
if os.path.exists(CSV_PATH):
    try:
        with open(CSV_PATH, "r") as f:
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

    # 1. Ping latency to 1.1.1.1
    ping_ms = last_known["ping_ms"]
    try:
        p = subprocess.run(["ping", "-c", "2", "-W", "1000", "1.1.1.1"], capture_output=True, text=True, timeout=3)
        for line in p.stdout.splitlines():
            if "avg" in line:
                ping_ms = line.split("/")[4]
    except Exception:
        pass

    # 2. Remote script for UniFi Gateway + SFP stick
    remote_script = r"""
python3 -c '
import subprocess, re, time

# 1. Ensure management IP exists on eth6
subprocess.run("ip -4 addr show dev eth6 | grep -q \"192.168.1.2/24\" || ip addr add 192.168.1.2/24 dev eth6 2>/dev/null", shell=True)

# 2. Read SFP optics via ethtool
ethtool_out = subprocess.run(["ethtool", "-m", "eth6"], capture_output=True, text=True).stdout
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
    subprocess.run("ip addr add 192.168.1.2/24 dev eth6 2>/dev/null", shell=True)
    time.sleep(0.5)
    stick_out = get_stick()

lines = stick_out.strip().splitlines()
c0 = f"{int(lines[0])/1000:.1f}" if len(lines) > 0 and lines[0].isdigit() else ""
c1 = f"{int(lines[1])/1000:.1f}" if len(lines) > 1 and lines[1].isdigit() else ""
ploam = "O5.1" if "current=51" in stick_out else ("O1.2" if "current=12" in stick_out else ("O5.1" if c0 else ""))

print(f"{optic_temp}|{c0}|{c1}|{ploam}|{rx_pwr}|{tx_pwr}|{bias}")
'
"""
    try:
        res = subprocess.run(
            ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=4", "--", "root@192.168.0.254", remote_script],
            capture_output=True, text=True, timeout=8
        )
        raw_out = res.stdout.strip()
        data_lines = [ln.strip() for ln in raw_out.splitlines() if "|" in ln]
        last_line = data_lines[-1] if data_lines else raw_out
        parts = last_line.split("|")
        if len(parts) == 7:
            optic_t, c0, c1, ploam, rx, tx, bias = parts
            with telemetry_lock:
                if optic_t: last_known["optic_temp"] = optic_t
                if c0 and c0 not in ("0", "0.0"):
                    last_known["cpu0_temp"] = c0
                    last_known["cpu1_temp"] = c1
                if ploam: last_known["ploam"] = ploam
                if rx: last_known["rx_power"] = rx
                if tx: last_known["tx_power"] = tx
                if bias: last_known["bias_ma"] = bias
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
            with open(CSV_PATH, "a") as f:
                f.write(f"{sample['timestamp']},{sample['optic_temp']},{sample['cpu0_temp']},{sample['cpu1_temp']},{sample['ploam']},{sample['rx_power']},{sample['tx_power']},{sample['bias_ma']},{sample['ping_ms']}\n")
            print(f"[{sample['timestamp']}] Logged: Optic={sample['optic_temp']}C, CPU0={sample['cpu0_temp']}C, PLOAM={sample['ploam']}, Ping={sample['ping_ms']}ms", flush=True)
        time.sleep(300)

class HotBoiHTTPHandler(http.server.BaseHTTPRequestHandler):
    timeout = 10

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
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            with telemetry_lock:
                payload = json.dumps(last_known).encode("utf-8")
            self.wfile.write(payload)

        elif self.path in ("/hotboi.apk", "/app.apk"):
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
        # Suppress noisy logs
        return

class HotBoiThreadingTCPServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

def main():
    print("=" * 60)
    print("🔥 HotBoi Thermal Server & Android Host")
    print(f"👉 Mobile Dashboard: http://192.168.0.134:{PORT}")
    print(f"👉 Direct APK Download: http://192.168.0.134:{PORT}/hotboi.apk")
    print(f"👉 REST API: http://192.168.0.134:{PORT}/api/telemetry")
    print("=" * 60, flush=True)

    # Start background polling thread
    t = threading.Thread(target=background_poller, daemon=True)
    t.start()

    # Start HTTP server
    with HotBoiThreadingTCPServer(("", PORT), HotBoiHTTPHandler) as httpd:
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\nShutting down HotBoi server.")

if __name__ == "__main__":
    main()
