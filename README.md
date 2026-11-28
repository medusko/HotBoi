# HotBoi — Universal SFP+ / SFP28 Optical & Gateway Thermal Monitor

**HotBoi** is an ultra-lightweight, standalone native Android telemetry monitor, home screen widget, and thermal watchdog for network gateways, switches, and **SFP+ / SFP28 optical transceivers** (UniFi Cloud Gateway, MikroTik, OpenWrt, EdgeOS, Debian/Linux routers, and XGS-PON sticks).

---

## 🧪 Google Play Closed Testing (Join the Beta)

- **Google Group:** https://groups.google.com/g/medusko-app-testers
- **Web Link (Join test):** https://play.google.com/apps/testing/com.medusko.hotboi
- **Android Link (Download):** https://play.google.com/store/apps/details?id=com.medusko.hotboi

### Instructions:
1. Join the **Medusko App Testers** Google Group above using your Google account.
2. Click the Web link to opt-in ("Become a tester").
3. Download **HotBoi** from the Android Play Store link.
4. Keep the app installed for 14 days to help complete closed testing!

---

## 📥 Downloads (Release Candidate 3)

All updates and release notes are documented in [**CHANGELOG.md**](CHANGELOG.md):

- 📱 **[Download Latest HotBoi Release (`v1.0.2-RC3`)](https://github.com/medusko/HotBoi/releases)** *(4.6 MB R8 full-mode minified APK, Medusko Home Screen Thermal Widget, pooled POSIX SSH engine, Cobalt & Warm Linen/Obsidian design parity with Keyboardy & Lagatron)*
- 📋 **[View Full Changelog & Release Notes](CHANGELOG.md)**

---

## ⚡ Architecture & Key Capabilities

1. **Pooled On-Device SSH Telemetry Engine (`Zero Python Dependency`)**:
   - Connects directly from your Android phone over SSH (`Ed25519`, `RSA`, or password) via `JSch` + `BouncyCastle` with strict `0600` TOFU `ssh_known_hosts` fingerprint pinning.
   - Runs a pure POSIX `/bin/sh` + `awk` probe (`ethtool -m`, `/sys/class/thermal`, concurrent ICMP `ping`), collecting laser optics temperature, dual-core CPU temperatures, Rx/Tx optical power (dBm), laser bias current (mA), and latency in ~150 ms.
   - Live **Gateway Port Scanner** discovers optical DOM interfaces (`eth6`, `eth7`, `sfp1`, etc.) automatically with isolated per-port JSONL history.

2. **Medusko Home Screen Thermal Widget**:
   - Resizable `4x2` Day/Night Android Home Screen widget displaying **Optic Temp**, **CPU 0 Temp**, **Rx Power (dBm)**, **Ping Latency**, active link/PLOAM state pill, and **1-tap live refresh** with an inline spinner.
   - Pin directly in 1 tap from **Settings → Appearance & Units → Pin Home Screen Thermal Widget**.

3. **Automated Background Watchdog & Thermal Trip Push Alerts**:
   - Periodic Android **`WorkManager`** polling with network constraints and high-priority push notifications whenever optical or CPU temperature crosses your configured threshold (default `79°C` / `174.2°F`).

4. **Security & Battery Efficiency by Default**:
   - **AES-256-GCM Hardware Keystore**: Fail-closed credential encryption for SSH passwords, private keys, and server API tokens.
   - **WAN HTTPS Enforcement**: Blocks cleartext HTTP to public hosts while supporting authenticated (`X-HotBoi-Token`) RFC1918/loopback/Tailscale LAN collector servers (`macOS`, `Windows`, `Linux`).
   - **Append-Only JSONL + `WebViewAssetLoader`**: O(1) single-line disk writes and ~300-byte `WebMessageCompat` chart deltas with automatic screen-off & background timer suspension.

---

## 🏪 Building From Source

- **Compile / Target SDK**: `36`
- **Min SDK**: `26` (Android 8.0)

```bash
./gradlew testDebugUnitTest assembleRelease bundleRelease
```
- **Release APK**: `app/build/outputs/apk/release/app-release.apk` (~4.6 MB minified)
- **Play Store Bundle**: `app/build/outputs/bundle/release/app-release.aab`

---

## 📬 Contact & Support

- **Website:** [medusko.com](https://medusko.com)
- **Support Contact:** [support@medusko.com](mailto:support@medusko.com)
- **Social:** [@meduskodev on Twitter](https://twitter.com/meduskodev)
