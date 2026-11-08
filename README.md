# HotBoi 🔥 — XGS-PON SFP+ Thermal Monitor

**HotBoi** is an ultra-lightweight, standalone native Android telemetry monitor and thermal watchdog for your **UniFi Cloud Gateway (White Bean / UCG-Fiber)** and **XGS-PON SFP+ optical stick transceiver**.

---

## 📥 Downloads (Release Candidate 1)

All updates and release notes are documented in our [**CHANGELOG.md**](CHANGELOG.md):

- 📱 **[Download HotBoi for Android (`HotBoi-Android-v1.0.0-RC1.apk`)](https://github.com/medusko/HotBoi/releases/tag/v1.0.0-RC1)** *(Latest Release Candidate — 5.0 MB R8 minified, dedicated About & Diagnostics screen, 1-tap quick theme toggle, screen-off battery awareness, tactile haptics)*
- 📋 **[View Full Changelog & Release Notes](CHANGELOG.md)**

---

## ⚡ Self-Contained In-App Architecture

HotBoi runs **100% on your Android phone** without requiring an external machine or cloud service:

1. **Direct On-Device SSH Telemetry Engine**:
   - Directly connects from your phone to your UniFi Gateway (`192.168.0.254:22`) over home Wi-Fi via embedded `JSch` and `BouncyCastle` Ed25519 authentication.
   - Collects laser optics temp, CPU Core 0/1 temps, optical Rx/Tx power, laser bias current, and gateway ping latency in ~1.2s.
   - Saves to the local on-device database and dynamically updates the dashboard and Chart.js graphs.

2. **Automated Background Watchdog & Thermal Trip Alerts**:
   - Uses Android's official **`WorkManager`** with Wi-Fi network constraints.
   - Periodically polls the gateway in the background and logs the reading into local history.
   - **Thermal Trip Alerts**: If temperatures exceed the configured safe threshold (default: 79°C), HotBoi fires a high-priority Android push notification.

3. **Screen-Off & Radio Power Optimizations (Parity with Lagatron)**:
   - Foreground auto-refresh automatically detects screen sleep (`PowerManager.isInteractive`) to prevent waking up Wi-Fi or cellular radios when the screen is dark.
   - R8 ProGuard bytecode minification and resource shrinking cuts release APK size by 50% (down to 5.0 MB).

4. **Multi-Mode Server Fallback (Optional)**:
   - Supports optional remote telemetry collection via macOS daemon, Windows background service, or Linux daemon when away from home.

---

## ✨ Features & Interface

- **Dedicated "About HotBoi" Diagnostics**: Tap the `(i)` info button in the top bar to inspect live engine connection state, active SFP+ interface (`eth6` / `eth8`), polling cadence, architecture pillars, and GitHub release links.
- **1-Tap Quick Theme Toggle**: Switch instantly between Light Mode and Dark Mode with the Sun/Moon button right from the top bar.
- **Tactile Haptic Feedback**: Subtle physical haptic response on pull-to-refresh, manual sync, and °C / °F unit toggle.
- **Dual Temperature Units**: Instant one-tap conversion between Celsius (°C) and Fahrenheit (°F).
- **Error Diagnostics Viewer**: Dedicated modal with copyable stack trace and active system reflection.

---

## 📱 Installing on Your Phone

The Release Candidate APK is ready at:  
👉 `HotBoi-Android-v1.0.0-RC1.apk`

### Option A: USB Sideload (Fastest if ADB connected)
```bash
adb install -r HotBoi-Android-v1.0.0-RC1.apk
```

### Option B: One-Tap Download from Your Phone Browser
Start the local server temporarily on your Mac:
```bash
python3 server/hotboi_server.py
```
On your phone (connected to the same Wi-Fi), open Chrome and navigate to:  
👉 **`http://192.168.0.134:8080/hotboi.apk`**

---

## ⚙️ Initial App Setup

1. Open **HotBoi** on your phone.
2. Tap the **Settings (gear)** icon in the top right.
3. Verify your Gateway settings:
   - **Gateway IP**: `192.168.0.254`
   - **SSH Port**: `22`
   - **SSH User**: `root`
   - **SSH Password**: *(Enter your UniFi OS root SSH password)*
   - **Background Collection**: Checked (Every 1 Hour)
   - **Trip Alert Threshold**: `79°C`
4. Tap **Save**. HotBoi will immediately connect to your UniFi gateway and display live telemetry!

---

## 🏪 Building From Source

- **Target SDK**: `35` (Android 15)
- **Min SDK**: `26` (Android 8.0)

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
./gradlew assembleRelease
```
Output: `app/build/outputs/apk/release/app-release.apk` (5.0 MB minified)

---

## 📬 Contact & Support

- **Website:** [medusko.com](https://medusko.com)
- **Support Contact:** [support@medusko.com](mailto:support@medusko.com)
- **Social:** [@meduskodev on Twitter](https://twitter.com/meduskodev)

