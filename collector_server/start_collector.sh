#!/usr/bin/env bash
set -e
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
echo "Starting HotBoi Telemetry Collector & Web Server..."
python3 "${DIR}/hotboi_collector.py" "$@"
