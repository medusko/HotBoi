#!/usr/bin/env bash
# ==============================================================================
# HotBoi Linux Service Installer (Debian / Ubuntu)
# Configures HotBoi Telemetry Collector as a persistent systemd service on port 8080
# ==============================================================================
set -e

if [ "$EUID" -ne 0 ]; then
  echo "[!] Please run with sudo or as root:"
  echo "    sudo bash $0"
  exit 1
fi

SRC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET_DIR="/opt/hotboi"
SERVICE_PATH="/etc/systemd/system/hotboi.service"

echo "[*] Installing HotBoi Telemetry Daemon for Debian/Ubuntu..."

# 1. Ensure target directory exists and copy files
mkdir -p "${TARGET_DIR}"
cp -r "${SRC_DIR}"/* "${TARGET_DIR}/"
chmod +x "${TARGET_DIR}/hotboi_collector.py"

# 2. Check for python3
if ! command -v python3 &>/dev/null; then
  echo "[*] Installing python3 and openssh-client..."
  apt-get update -qq && apt-get install -y -qq python3 openssh-client
fi

# 3. Create systemd service unit
cat <<EOF > "${SERVICE_PATH}"
[Unit]
Description=HotBoi Universal SFP+ Telemetry Collector & API Server
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=${TARGET_DIR}
ExecStart=/usr/bin/python3 ${TARGET_DIR}/hotboi_collector.py --interval 30 --http-port 8080
Restart=always
RestartSec=5s

# Hardening & Limits
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
EOF

# 4. Reload systemd, enable and start service
systemctl daemon-reload
systemctl enable hotboi.service
systemctl restart hotboi.service

# 5. Open firewall port 8080 if UFW is active
if command -v ufw &>/dev/null && ufw status | grep -q "Status: active"; then
  echo "[*] Allowing port 8080 through UFW..."
  ufw allow 8080/tcp comment "HotBoi Telemetry Server"
fi

echo ""
echo "=========================================================="
echo "✅ HotBoi Linux Daemon Installed & Running!"
echo "   Status:   systemctl status hotboi"
echo "   Logs:     journalctl -u hotboi -f"
echo "   API Test: curl http://localhost:8080/api/telemetry"
echo "=========================================================="
