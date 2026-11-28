#!/usr/bin/env bash
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET_DIR="${HOME}/.hotboi"
PLIST_PATH="${HOME}/Library/LaunchAgents/com.medusko.hotboi.plist"
BIND_ADDR="${HOTBOI_BIND:-0.0.0.0}"
HTTP_PORT="${HOTBOI_PORT:-8080}"
API_TOKEN="${HOTBOI_API_TOKEN:-}"

echo "Installing HotBoi macOS Collector Service..."
mkdir -p "${TARGET_DIR}"
cp -r "${DIR}"/* "${TARGET_DIR}/"
chmod +x "${TARGET_DIR}/hotboi_collector.py"

mkdir -p "${HOME}/Library/LaunchAgents"

cat <<EOF > "${PLIST_PATH}"
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.medusko.hotboi</string>
    <key>ProgramArguments</key>
    <array>
        <string>/usr/bin/python3</string>
        <string>${TARGET_DIR}/hotboi_collector.py</string>
        <string>--interval</string>
        <string>30</string>
        <string>--bind</string>
        <string>${BIND_ADDR}</string>
        <string>--http-port</string>
        <string>${HTTP_PORT}</string>
    </array>
    <key>EnvironmentVariables</key>
    <dict>
        <key>HOTBOI_API_TOKEN</key>
        <string>${API_TOKEN}</string>
    </dict>
    <key>WorkingDirectory</key>
    <string>${TARGET_DIR}</string>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>StandardOutPath</key>
    <string>${TARGET_DIR}/hotboi_collector.log</string>
    <key>StandardErrorPath</key>
    <string>${TARGET_DIR}/hotboi_collector.err</string>
</dict>
</plist>
EOF

chmod 600 "${PLIST_PATH}"
launchctl unload "${PLIST_PATH}" 2>/dev/null || true
launchctl load "${PLIST_PATH}"

echo "HotBoi macOS Service installed and running on ${BIND_ADDR}:${HTTP_PORT}"
if [ -n "${API_TOKEN}" ]; then
    echo "API Token protection ENABLED (enter the same token in HotBoi Android Settings -> Server API Token)."
else
    echo "Tip: Re-run with HOTBOI_API_TOKEN='your-secret' ./install_macos_service.sh to require X-HotBoi-Token auth."
fi
