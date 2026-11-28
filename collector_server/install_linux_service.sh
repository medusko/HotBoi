#!/usr/bin/env bash
# ==============================================================================
# HotBoi Linux Service Installer (Debian / Ubuntu)
# Configures HotBoi Telemetry Collector as a persistent systemd service on port 8080
# Supports HOTBOI_BIND (default 0.0.0.0) and HOTBOI_API_TOKEN environment variables
# ==============================================================================
set -e

if [ "$EUID" -ne 0 ]; then
  echo "[!] Please run with sudo or as root:"
  echo "    sudo HOTBOI_API_TOKEN='optional-secret' bash $0"
  exit 1
fi

SRC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET_DIR="/opt/hotboi"
ENV_FILE="${TARGET_DIR}/.env"
SERVICE_PATH="/etc/systemd/system/hotboi.service"
BIND_ADDR="${HOTBOI_BIND:-0.0.0.0}"
HTTP_PORT="${HOTBOI_PORT:-8080}"
API_TOKEN="${HOTBOI_API_TOKEN:-}"

echo "[*] Installing HotBoi Telemetry Daemon for Debian/Ubuntu..."

mkdir -p "${TARGET_DIR}"
cp -r "${SRC_DIR}"/* "${TARGET_DIR}/"
chmod +x "${TARGET_DIR}/hotboi_collector.py"

if ! command -v python3 &>/dev/null; then
  echo "[*] Installing python3 and openssh-client..."
  apt-get update -qq && apt-get install -y -qq python3 openssh-client
fi

cat <<EOF > "${ENV_FILE}"
HOTBOI_API_TOKEN=${API_TOKEN}
EOF
chmod 600 "${ENV_FILE}"

cat <<EOF > "${SERVICE_PATH}"
[Unit]
Description=HotBoi Universal SFP+ Telemetry Collector & API Server
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=${TARGET_DIR}
EnvironmentFile=${ENV_FILE}
ExecStart=/usr/bin/python3 ${TARGET_DIR}/hotboi_collector.py --interval 30 --bind ${BIND_ADDR} --http-port ${HTTP_PORT}
Restart=always
RestartSec=5s
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable hotboi.service
systemctl restart hotboi.service

if [ "${BIND_ADDR}" = "0.0.0.0" ] && command -v ufw &>/dev/null && ufw status | grep -q "Status: active"; then
  echo "[*] Allowing port ${HTTP_PORT}/tcp through UFW..."
  ufw allow "${HTTP_PORT}/tcp" comment "HotBoi Telemetry Server"
fi

echo ""
echo "=========================================================="
echo "HotBoi Linux Daemon Installed & Running (${BIND_ADDR}:${HTTP_PORT})"
echo "  Status:   systemctl status hotboi"
echo "  Logs:     journalctl -u hotboi -f"
echo "  API Test: curl http://localhost:${HTTP_PORT}/api/telemetry"
if [ -n "${API_TOKEN}" ]; then
  echo "  Auth:     X-HotBoi-Token ENABLED (stored in ${ENV_FILE})"
else
  echo "  Tip:      Set HOTBOI_API_TOKEN in ${ENV_FILE} and restart to enforce token auth."
fi
echo "=========================================================="
