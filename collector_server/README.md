# HotBoi SFP+ Telemetry Collector Server (BETA)

Cross-platform optical transceiver and gateway hardware thermal monitoring service for **macOS**, **Windows 10/11**, and **Linux (Debian / Ubuntu)**.

HotBoi connects via SSH (`StrictHostKeyChecking=accept-new`) using a lightweight pure POSIX `/bin/sh` + `awk` probe (zero `python3` dependency on the gateway), polls SFP+/SFP28 optical diagnostics (temperature, laser bias current, optical TX/RX power, CPU core temperatures, PLOAM state, and ping latency), logs bounded history to CSV/JSON, and serves the Medusko-themed HTTP dashboard and JSON REST API.

---

## Security & CLI Flags

When run directly (`python3 hotboi_collector.py`), the collector binds to **`127.0.0.1:8080` by default** so it is not exposed on untrusted networks unless requested:

| Flag | Default | Description |
| :--- | :--- | :--- |
| `--bind` | `127.0.0.1` | Pass `--bind 0.0.0.0` to allow LAN connections from the HotBoi Android app |
| `--http-port` | `8080` | HTTP server port |
| `--api-token` | `$HOTBOI_API_TOKEN` | Optional shared secret verified via `X-HotBoi-Token` or `Authorization: Bearer` |
| `--pon-stick-subnet` | `false` | Opt-in flag to assign `192.168.1.2/24` on the SFP+ port when probing `192.168.1.1` PON sticks |
| `--host` / `--port` / `--user` | `192.168.0.254` / `22` / `root` | Gateway SSH target |
| `--sfp` | `eth6` | Gateway SFP+ / SFP28 Linux network interface |
| `--ping` | `1.1.1.1` | ICMP latency probe target |
| `--interval` | `60` | Poll interval in seconds |

---

## macOS Deployment (`launchd` Service)

Install as an automatic background service (binds to `0.0.0.0:8080` by default for LAN phone access; override with `HOTBOI_BIND=127.0.0.1` or protect with `HOTBOI_API_TOKEN`):

```bash
chmod +x install_macos_service.sh
HOTBOI_API_TOKEN="your-lan-secret" ./install_macos_service.sh
```

- **Installed Directory**: `~/.hotboi/`
- **Service Configuration**: `~/Library/LaunchAgents/com.medusko.hotboi.plist` (`chmod 600`)
- **View Live Logs**: `tail -f ~/.hotboi/hotboi_collector.log`

---

## Windows 10 / 11 Deployment

```powershell
# Allow LAN mobile access on port 8080 (Administrator PowerShell)
New-NetFirewallRule -DisplayName "HotBoi Server Port 8080" -Direction Inbound -LocalPort 8080 -Protocol TCP -Action Allow

# Launch collector bound to LAN with optional API token
powershell -ExecutionPolicy Bypass -File .\start_collector.ps1 --bind 0.0.0.0 --api-token "your-lan-secret"
```

---

## Linux Deployment (Debian / Ubuntu `systemd`)

```bash
sudo HOTBOI_API_TOKEN="your-lan-secret" bash install_linux_service.sh
```

- **Target Directory**: `/opt/hotboi/` (token stored `0600` in `/opt/hotboi/.env`)
- **Systemd Unit**: `/etc/systemd/system/hotboi.service`
- **Service Controls**: `sudo systemctl status hotboi` / `journalctl -u hotboi -f`

---

## REST API Specification

When `--api-token` / `HOTBOI_API_TOKEN` is configured, include `X-HotBoi-Token: <token>` on all `/api/*` requests (supported natively in **HotBoi Android Settings → Server API Token**).

- `GET /api/telemetry` — Latest real-time telemetry sample JSON
- `GET /api/history` — Full bounded 2,500-sample chart history bundle
- `GET /api/config` & `POST /api/config` — Read or update `ping_target` (cross-origin browser `POST` requests are blocked)
- `GET /health` — Unauthenticated `200 OK` liveness probe
