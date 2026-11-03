#!/usr/bin/env python3
"""
HotBoi Telemetry Collector & Local Web Server
Cross-platform: macOS, Windows 10/11, and Linux.

Collects telemetry from UniFi Gateway SFP+ optical module, logs to CSV & JSON,
and serves a local HTTP dashboard & API on port 8080.
"""

import sys
import os
import re
import time
import json
import socket
import base64
import platform
import argparse
import subprocess
import threading
from datetime import datetime
from pathlib import Path
from http.server import SimpleHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse

# Default Settings
DEFAULT_GATEWAY = "192.168.0.254"
DEFAULT_USER = "root"
DEFAULT_PORT = 22
DEFAULT_SFP_IFACE = "eth6"
DEFAULT_PING_TARGET = "1.1.1.1"
DEFAULT_INTERVAL_SEC = 60
DEFAULT_HTTP_PORT = 8080

SCRIPT_DIR = Path(__file__).parent.resolve()
CSV_FILE = SCRIPT_DIR / "telemetry_history.csv"
JSON_FILE = SCRIPT_DIR / "telemetry_history.json"
DASHBOARD_FILE = SCRIPT_DIR / "dashboard.html"

# In-memory latest sample cache & dynamic configuration
current_ping_target = DEFAULT_PING_TARGET
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
    "device_name": "UniFi Cloud Gateway Fiber (White Bean)",
    "sfp_port": "Port 7 SFP+"
}

telemetry_lock = threading.Lock()

def get_default_ssh_key():
    home = Path.home()
    for name in ["id_ed25519", "id_rsa"]:
        candidate = home / ".ssh" / name
        if candidate.is_file():
            return str(candidate)
    return None

def ping_host(target: str) -> float:
    """Cross-platform ping returning average latency in ms."""
    is_win = platform.system() == "Windows"
    cmd = ["ping", "-n", "2", "-w", "1000", target] if is_win else ["ping", "-c", "2", "-W", "1", target]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=3)
        out = proc.stdout
        if is_win:
            # Average = 8ms
            m = re.search(r"Average = ([\d\.]+)ms", out, re.IGNORECASE)
            if m:
                return float(m.group(1))
        else:
            # rtt min/avg/max/mdev = 7.123/8.456/...
            m = re.search(r"avg.*?=\s*[\d\.]+/([\d\.]+)/", out)
            if m:
                return float(m.group(1))
    except Exception:
        pass
    return current_telemetry.get("ping_ms", 8.0)

def query_gateway_ssh(host: str, port: int, user: str, key_path: str, sfp_iface: str, ping_target: str) -> dict:
    raw_script = f"""import subprocess, re, time

subprocess.run("ip -4 addr show dev {sfp_iface} | grep -q \\"192.168.1.2/24\\" || ip addr add 192.168.1.2/24 dev {sfp_iface} 2>/dev/null", shell=True)

ethtool_out = subprocess.run(["ethtool", "-m", "{sfp_iface}"], capture_output=True, text=True).stdout
temp_m = re.search(r"Module temperature\\s+:\\s+([\\d\\.]+)\\s+degrees C", ethtool_out)
rx_m = re.search(r"Receiver signal average optical power\\s+:\\s+[\\d\\.]+\\s+mW\\s+/\\s+([-\\d\\.]+)\\s+dBm", ethtool_out)
tx_m = re.search(r"Laser output power\\s+:\\s+[\\d\\.]+\\s+mW\\s+/\\s+([-\\d\\.]+)\\s+dBm", ethtool_out)
bias_m = re.search(r"Laser bias current\\s+:\\s+([\\d\\.]+)\\s+mA", ethtool_out)

optic_temp = temp_m.group(1) if temp_m else ""
rx_pwr = rx_m.group(1) if rx_m else ""
tx_pwr = tx_m.group(1) if tx_m else ""
bias = bias_m.group(1) if bias_m else ""

def get_stick():
    return subprocess.run(["ssh", "-o", "StrictHostKeyChecking=no", "-o", "ConnectTimeout=2", "root@192.168.1.1",
      "cat /sys/class/thermal/thermal_zone0/temp; cat /sys/class/thermal/thermal_zone1/temp; pon psg"],
      capture_output=True, text=True, timeout=3).stdout

stick_out = get_stick()
if not stick_out:
    time.sleep(0.5)
    stick_out = get_stick()

lines = stick_out.strip().splitlines()
c0 = f"{{int(lines[0])/1000:.1f}}" if len(lines) > 0 and lines[0].isdigit() else ""
c1 = f"{{int(lines[1])/1000:.1f}}" if len(lines) > 1 and lines[1].isdigit() else ""
ploam = "O5.1" if "current=51" in stick_out else ("O1.2" if "current=12" in stick_out else ("O5.1" if c0 else ""))

ping_out = subprocess.run(["ping", "-c", "2", "-W", "1", "{ping_target}"], capture_output=True, text=True).stdout
p_m = re.search(r"avg.*?=\\s*[\\d\\.]+/([\\d\\.]+)/", ping_out)
ping_val = p_m.group(1) if p_m else "8.5"

print(f"{{optic_temp}}|{{c0}}|{{c1}}|{{ploam}}|{{rx_pwr}}|{{tx_pwr}}|{{bias}}|{{ping_val}}")
"""
    b64_script = base64.b64encode(raw_script.encode("utf-8")).decode("ascii")
    remote_command = f"python3 -c \"import base64; exec(base64.b64decode('{b64_script}').decode('utf-8'))\""

    ssh_cmd = [
        "ssh",
        "-o", "StrictHostKeyChecking=no",
        "-o", "ConnectTimeout=5",
        "-p", str(port),
    ]
    if key_path:
        ssh_cmd.extend(["-i", key_path])
    ssh_cmd.extend([f"{user}@{host}", remote_command])

    proc = subprocess.run(ssh_cmd, capture_output=True, text=True, timeout=12)
    if proc.returncode != 0:
        raise RuntimeError(f"SSH failed ({proc.returncode}): {proc.stderr.strip()}")

    output = proc.stdout.strip()
    last_line = output.splitlines()[-1] if output else ""
    parts = last_line.split("|")
    if len(parts) < 8:
        raise ValueError(f"Malformed output: {output}")

    optic_temp = float(parts[0]) if parts[0] else current_telemetry["optic_temp"]
    c0 = float(parts[1]) if parts[1] else current_telemetry["cpu0_temp"]
    c1 = float(parts[2]) if parts[2] else current_telemetry["cpu1_temp"]
    ploam = parts[3] if parts[3] else current_telemetry["ploam"]
    rx = float(parts[4]) if parts[4] else current_telemetry["rx_power"]
    tx = float(parts[5]) if parts[5] else current_telemetry["tx_power"]
    bias = float(parts[6]) if parts[6] else current_telemetry["bias_ma"]
    ping_val = float(parts[7]) if parts[7] else current_telemetry["ping_ms"]

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
        "device_name": "UniFi Cloud Gateway Fiber (White Bean)",
        "sfp_port": "Port 7 SFP+"
    }

def record_sample(sample: dict):
    with telemetry_lock:
        current_telemetry.update(sample)

    # Append to CSV
    write_header = not CSV_FILE.exists()
    with open(CSV_FILE, "a", encoding="utf-8") as f:
        if write_header:
            f.write("timestamp,optic_temp,cpu0_temp,cpu1_temp,ploam,rx_power,tx_power,bias_ma,ping_ms\n")
        f.write(f"{sample['timestamp']},{sample['optic_temp']},{sample['cpu0_temp']},{sample['cpu1_temp']},"
                f"{sample['ploam']},{sample['rx_power']},{sample['tx_power']},{sample['bias_ma']},{sample['ping_ms']}\n")

    # Update latest JSON
    with open(JSON_FILE, "w", encoding="utf-8") as f:
        json.dump(sample, f, indent=2)

def get_history_bundle(max_samples=2500):
    samples = []
    if CSV_FILE.is_file():
        try:
            with open(CSV_FILE, "r", encoding="utf-8") as f:
                lines = f.readlines()
                if len(lines) > 1:
                    data_lines = lines[1:][-max_samples:]
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
                                samples.append(s)
                            except ValueError:
                                pass
        except Exception as e:
            print(f"[!] Error reading CSV history: {e}", flush=True)

    with telemetry_lock:
        latest = dict(current_telemetry)

    if not samples:
        samples = [latest]

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

    with telemetry_lock:
        target = current_ping_target

    return {
        "latest": latest,
        "sampleCount": len(samples),
        "samples": samples,
        "labels": labels,
        "fullTimestamps": fullTimestamps,
        "opticData": opticData,
        "cpu0Data": cpu0Data,
        "cpu1Data": cpu1Data,
        "pingData": pingData,
        "deviceName": latest.get("device_name", "Universal SFP+ Device"),
        "portName": latest.get("sfp_port", "Port 7 SFP+"),
        "pingTarget": target,
        "ping_target": target,
        "mode": "server"
    }

def collection_loop(host, port, user, key_path, sfp_iface, interval):
    print(f"[*] Starting telemetry polling every {interval}s from {user}@{host}:{port} ({sfp_iface})...", flush=True)
    while True:
        try:
            with telemetry_lock:
                target = current_ping_target
            sample = query_gateway_ssh(host, port, user, key_path, sfp_iface, target)
            sample["ping_target"] = target
            record_sample(sample)
            print(f"[{sample['timestamp']}] Laser: {sample['optic_temp']}°C | CPU0: {sample['cpu0_temp']}°C | Rx: {sample['rx_power']} dBm | Ping ({target}): {sample['ping_ms']} ms", flush=True)
        except Exception as e:
            print(f"[!] Polling Error: {e}", flush=True)
        time.sleep(interval)

class HotBoiHttpHandler(SimpleHTTPRequestHandler):
    def log_message(self, format, *args):
        # Quiet standard HTTP logs to keep console clean
        pass

    def do_OPTIONS(self):
        self.send_response(200)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def do_POST(self):
        parsed = urlparse(self.path)
        if parsed.path in ["/api/config"]:
            content_length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(content_length).decode("utf-8")
            try:
                data = json.loads(body)
                global current_ping_target
                with telemetry_lock:
                    if "ping_target" in data and data["ping_target"]:
                        current_ping_target = str(data["ping_target"]).strip()
                        current_telemetry["ping_target"] = current_ping_target
                        print(f"[*] Dynamic config update: ping target set to {current_ping_target}", flush=True)
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                self.wfile.write(json.dumps({"status": "ok", "ping_target": current_ping_target}).encode("utf-8"))
            except Exception as e:
                self.send_response(400)
                self.send_header("Content-Type", "application/json")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                self.wfile.write(json.dumps({"error": str(e)}).encode("utf-8"))
            return
        self.send_response(404)
        self.end_headers()

    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path in ["/api/telemetry", "/telemetry.json"]:
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            with telemetry_lock:
                data = json.dumps(current_telemetry).encode("utf-8")
            self.wfile.write(data)
            return

        if parsed.path in ["/api/history", "/history.json"]:
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            data = json.dumps(get_history_bundle()).encode("utf-8")
            self.wfile.write(data)
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

        super().do_GET()

def main():
    parser = argparse.ArgumentParser(description="HotBoi SFP+ Telemetry Collector & Web Server")
    parser.add_argument("--host", default=DEFAULT_GATEWAY, help="UniFi Gateway IP (default: 192.168.0.254)")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="SSH port (default: 22)")
    parser.add_argument("--user", default=DEFAULT_USER, help="SSH user (default: root)")
    parser.add_argument("--key", default=get_default_ssh_key(), help="SSH private key path (e.g. ~/.ssh/id_ed25519)")
    parser.add_argument("--sfp", default=DEFAULT_SFP_IFACE, help="SFP+ interface (default: eth6)")
    parser.add_argument("--ping", default=DEFAULT_PING_TARGET, help="Ping target (default: 1.1.1.1)")
    parser.add_argument("--interval", type=int, default=DEFAULT_INTERVAL_SEC, help="Poll interval in seconds (default: 60)")
    parser.add_argument("--http-port", type=int, default=DEFAULT_HTTP_PORT, help="HTTP server port (default: 8080)")
    args = parser.parse_args()

    global current_ping_target
    current_ping_target = args.ping
    with telemetry_lock:
        current_telemetry["ping_target"] = current_ping_target

    # Start polling worker thread
    collector_thread = threading.Thread(
        target=collection_loop,
        args=(args.host, args.port, args.user, args.key, args.sfp, args.interval),
        daemon=True
    )
    collector_thread.start()

    # Start HTTP server
    os.chdir(SCRIPT_DIR)
    server_address = ("0.0.0.0", args.http_port)
    httpd = HTTPServer(server_address, HotBoiHttpHandler)
    print(f"[*] HotBoi Web Server running at http://localhost:{args.http_port}")
    print(f"[*] Telemetry API available at http://localhost:{args.http_port}/api/telemetry")
    print(f"[*] Press Ctrl+C to stop.")

    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping HotBoi Collector...")
        httpd.server_close()

if __name__ == "__main__":
    main()
