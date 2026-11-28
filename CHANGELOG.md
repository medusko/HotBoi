# Changelog

All notable changes to the **HotBoi** project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [v1.0.2-RC3] - 2026-11-27

### 🌟 Release Candidate 3 Highlights
- **Android Home Screen Thermal Widget**:
  - Added a resizable `4x2` Medusko-themed home screen widget (`HotBoiWidgetProvider`) displaying live **SFP+ Optic Temp**, **CPU Core 0 Temp**, **Optical Rx Power (dBm)**, **ICMP Ping Latency**, active link/PLOAM state pill (`O5.1`, `HOT!`, `CACHED`), and **1-tap live refresh** with an inline progress spinner.
  - Added **1-tap "Pin Home Screen Thermal Widget"** button in **Settings → Appearance & Units** via `AppWidgetManager.requestPinAppWidget`, plus automatic widget synchronization across °C/°F toggles, Light/Dark theme changes, foreground refreshes, and background `ThermalWorker` runs.
- **Unified Medusko Design System (Keyboardy & Lagatron Parity)**:
  - Aligned Light Mode (Warm Cream / Linen `#F6F1E8` & `#FFFDF9`) and Dark Mode (Dark Obsidian `#101216` & `#191C23`) with **Medusko Cobalt Blue (`#2E6BD9`)**, **Sky Blue (`#60A5FA`)**, **Warm Gold (`#FBBF24` / `#B45309`)**, **Violet**, and **Lagatron Orange (`#FF9100`)**, removing legacy green UI and chart accents across native dialogs and the WebView/web dashboards.
- **Pooled SSH Engine & Pure POSIX `/bin/sh` Probe**:
  - Replaced remote `python3` scripts with an injection-sanitized POSIX `/bin/sh` + `awk` probe (`ping -c 1 -W 1` overlapped with `ethtool -m`), cutting probe latency to ~150 ms and supporting `python3`-less switches and OpenWrt/BusyBox gateways.
  - Added authenticated `JSch` session pooling for foreground polling with automatic cleanup on `Activity.onStop()`.
- **Security Hardening**:
  - Added `network_security_config.xml` and `CollectorHttpClient` host validation enforcing HTTPS on all public/WAN endpoints while restricting cleartext HTTP strictly to private RFC1918/loopback/Tailscale/mDNS LAN collectors.
  - Hardened `CryptoHelper` to fail closed (never falling back to plaintext storage on Keystore errors) and zeroed temporary key/password `ByteArray` buffers in `finally` blocks.
  - Enforced strict `0600` TOFU `ssh_known_hosts` verification with live fingerprint inspection and a **Reset Host Keys** button in Settings, and gated `192.168.1.2/24` PON-stick IP assignment behind an explicit opt-in toggle (`cbPonStickAutoIp` / `--pon-stick-subnet`).
  - Hardened companion servers (`hotboi_collector.py`, `hotboi_server.py`) to bind to `127.0.0.1` by default, reject cross-origin browser `POST` mutations, and verify optional `X-HotBoi-Token` (`HOTBOI_API_TOKEN`) headers using `hmac.compare_digest`.
- **R8 Full-Mode, JSONL Storage & WebView Runtime Optimization**:
  - Enabled R8 full mode, added Baseline Profile rules (`baseline-prof.txt`), and stripped unused BouncyCastle Post-Quantum lookup tables, shrinking the release APK to **4.6 MB** (`-25%` compressed / `-2.26 MB` uncompressed).
  - Migrated local sample persistence to O(1) append-only `.jsonl` files and upgraded the dashboard to `WebViewAssetLoader` + `WebMessageCompat` single-sample incremental deltas (~300 bytes per poll).
  - Added `bundleRelease` (`.aab`) generation alongside `.apk` in GitHub Actions CI.

---

## [v1.0.0-RC2] - 2026-11-10

### 🌟 Release Candidate 2 Highlights
- **AdMob SDK & Google Ads Integration**:
  - Integrated official Google Mobile Ads SDK with test banner support.
  - Declared `AD_ID` permission and configured official AdMob application ID.
- **Android 15 / API 36 Target**:
  - Upgraded compileSdk and targetSdk to 36.
  - Added fail-fast release signing verification.
- **Penguin Mascot & Clean Production Icons**:
  - Redesigned Linux server mascot vector art with distinct penguin features (beak, flippers, belly).
  - Removed temporary RC2 branding badges from launcher drawables.
- **Security & Privacy Hardening**:
  - Removed default private key and scrubbed private key material from repository history.

---

### 🌟 Release Candidate 1.1 Highlights
- **Dual-Distribution & Play Store Compliance Foundation**:
  - Integrated compile-time `BuildConfig` flags (`SHOW_ADS`) supporting dual distribution flavors (Ad-supported Google Play vs. free & open-source GitHub builds).
- **Resource & Support Quick Links**:
  - Enhanced About HotBoi dialog with dedicated **Sponsor / Support ☕ ↗** link pointing directly to GitHub Sponsors alongside Changelog, Releases, and Source Code.
- **Build & Automation Modernization**:
  - Incremented version to **v1.0.0-RC1.1 (Build 3)**.
  - Added automated `/mass-update` pipeline (`scripts/mass_update.sh`) with wireless ADB deployment.

---

## [v1.0.0-RC1] - 2026-11-04

### 🌟 Release Candidate 1 Highlights
- **Information & Diagnostic Screens (Lagatron & Keyboardy Parity)**:
  - Added dedicated custom **About HotBoi Dialog** (`showAboutHotBoiDialog`) matching Medusko app design standards.
  - **Hero Identity Card** with launcher icon badge, dynamic version tag (`v1.0.0-RC1`), build number (`Build 2`), and target environment (`Built for Android 15 / Pixel`).
  - **Interactive Resource Quick Links**: *Full Changelog ↗*, *All Releases ↗*, and *Source Code ↗* launching GitHub.
- **Top App Bar Modernization & Quick Theming**:
  - Replaced legacy Android framework drawables with crisp custom Vector Drawables (`ic_info`, `ic_settings`, `ic_refresh`, `ic_light_mode`, `ic_dark_mode`, `ic_warning`).
  - Added **1-Tap Quick Light / Dark Theme Toggle** directly in the top app bar.
- **Haptic Tactile Feedback**:
  - Added subtle tactile vibrations (`KEYBOARD_TAP`) on pull-to-refresh, manual refresh, °C / °F unit toggle, theme switch, and dialog button interactions.
- **Performance, Battery & Radio Optimization**:
  - **Screen-Off Radio Power Awareness**: `autoRefreshRunnable` checks `PowerManager.isInteractive` and suspends network polling when device screen is turned off.
  - **R8 Minification & Resource Shrinking**: Enabled code shrinking and resource optimization on release builds (`isMinifyEnabled = true`, `isShrinkResources = true`).

---

## [v1.0.0-beta] - 2026-11-02

### Added
- Initial beta release of HotBoi.
- Standalone on-device SFP+ optical telemetry monitor.
- Direct SSH engine via JSch with BouncyCastle Ed25519 private key authentication.
- Real-time laser optics temperature, CPU Core 0/1 temps, optical Rx/Tx power, laser bias current, and gateway ping latency.
- Automated background WorkManager polling with high-priority thermal trip push notifications.
- Multi-mode server fallback (macOS, Windows, Linux), one-tap °C / °F conversion, and System / Light / Dark theme modes.
