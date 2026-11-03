#!/usr/bin/env bash
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET_DIR="${HOME}/.hotboi"
PLIST_PATH="${HOME}/Library/LaunchAgents/com.zlatty.hotboi.plist"

echo "Installing HotBoi macOS Collector Service..."
mkdir -p "${TARGET_DIR}"
cp -r "${DIR}"/* "${TARGET_DIR}/"

mkdir -p "${HOME}/Library/LaunchAgents"

cat <<EOF > "${PLIST_PATH}"
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.zlatty.hotboi</string>
    <key>ProgramArguments</key>
    <array>
        <string>/usr/bin/python3</string>
        <string>${TARGET_DIR}/hotboi_collector.py</string>
        <string>--interval</string>
        <string>30</string>
    </array>
    <key>WorkingDirectory</key>
    <string>${TARGET_DIR}</string>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>StandardOutPath</key>
    <string>/tmp/hotboi_collector.log</string>
    <key>StandardErrorPath</key>
    <string>/tmp/hotboi_collector.err</string>
</dict>
</plist>
EOF

launchctl unload "${PLIST_PATH}" 2>/dev/null || true
launchctl load "${PLIST_PATH}"

echo "HotBoi macOS Service installed and running at http://localhost:8080"
