# HotBoi SFP+ Telemetry Collector Server (BETA)

Cross-platform optical transceiver and gateway hardware thermal monitoring service for **macOS**, **Windows 10/11**, and **Linux (Debian / Ubuntu)**.

HotBoi connects via SSH to your gateway or switch (UniFi, MikroTik, Cisco, Mellanox, or generic PON transceivers), polls SFP+/SFP28 optical diagnostics (temperature, laser bias current, optical TX/RX power, CPU core temperatures, PLOAM state, and ping latency), logs history to CSV/JSON, and serves a high-performance HTTP dashboard and JSON REST API on port `8080`.

---

## 🍏 macOS Deployment (launchd Service)

Install as an automatic background service that survives reboots and user logouts:

```bash
chmod +x install_macos_service.sh
./install_macos_service.sh
```

- **Installed Directory**: `~/.hotboi/`
- **Service Configuration**: `~/Library/LaunchAgents/com.medusko.hotboi.plist`
- **View Live Logs**: `tail -f /tmp/hotboi_collector.log`
- **Dashboard**: `http://localhost:8080`
- **Telemetry API**: `http://localhost:8080/api/telemetry`

---

## 🪟 Windows 10 / 11 Deployment

Run the included background launcher or configure as a persistent service:

```powershell
# In PowerShell (Run as Administrator to allow port 8080 through Windows Firewall)
New-NetFirewallRule -DisplayName "HotBoi Server Port 8080" -Direction Inbound -LocalPort 8080 -Protocol TCP -Action Allow

# Launch collector persistently in background
powershell -ExecutionPolicy Bypass -File .\start_collector.ps1
```

- **Default Directory**: `C:\HotBoi\`
- **Firewall**: Port 8080 TCP Inbound Allow
- **Background Runner**: `start_collector.bat` / `start_collector.ps1`
- **Dashboard**: `http://localhost:8080`
- **Telemetry API**: `http://localhost:8080/api/telemetry`

---

## 🐧 Linux Deployment (Debian / Ubuntu systemd)

Install as a systemd service that automatically starts on system boot:

```bash
sudo bash install_linux_service.sh
```

- **Target Directory**: `/opt/hotboi/`
- **Systemd Unit**: `/etc/systemd/system/hotboi.service`
- **Service Controls**:
  - `sudo systemctl status hotboi`
  - `sudo systemctl restart hotboi`
  - `journalctl -u hotboi -f`
- **Firewall**: Automatically configures `ufw allow 8080/tcp` if UFW is active.
- **Dashboard**: `http://localhost:8080`
- **Telemetry API**: `http://localhost:8080/api/telemetry`

---

## 📡 REST API Specification

### `GET /api/telemetry` (or `/telemetry.json`)

Returns the latest real-time sample:

```json
{
  "timestamp": "2026-10-28 22:20:00",
  "optic_temp": 40.58,
  "cpu0_temp": 54.5,
  "cpu1_temp": 53.1,
  "ploam": "O5.1",
  "rx_power": -22.08,
  "tx_power": 4.51,
  "bias_ma": 13.82,
  "ping_ms": 5.92,
  "device_name": "UniFi Cloud Gateway Fiber (White Bean)",
  "sfp_port": "Port 7 SFP+"
}
```

### `GET /health`

Returns `200 OK` string for uptime monitors.
