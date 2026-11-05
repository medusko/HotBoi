# Changelog

All notable changes to the **HotBoi** project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [v1.0.0-RC1.1] - 2026-11-04

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
  - **Hero Identity Card** with fiery launcher icon badge, dynamic version tag (`v1.0.0-RC1`), build number (`Build 2`), and target environment (`Built for Android 15 / Pixel`).
  - **Live Quick Diagnostic Status Bar** displaying live engine state dot (`🟢 Direct SSH` vs `🔴 Standby / Offline`), target host (`192.168.0.254:22`), active SFP+ optical port (`eth6` / `eth8`), and thermal trip limit (`79°C`).
  - **Zero-Overhead Standalone Architecture** breakdown and 6 core telemetry feature pillars.
  - **Release Candidate 1 & What's New** card with release improvements.
  - **Interactive Resource Quick Links**: *Full Changelog ↗*, *All Releases ↗*, and *Source Code ↗* launching GitHub.
- **Top App Bar Modernization & Quick Theming**:
  - Replaced legacy Android framework drawables with crisp custom Vector Drawables (`ic_info`, `ic_settings`, `ic_refresh`, `ic_light_mode`, `ic_dark_mode`, `ic_warning`).
  - Added **1-Tap Quick Light / Dark Theme Toggle** directly in the top app bar (Sun icon in Dark Mode, Moon icon in Light Mode) for immediate theme switching without navigating into settings.
  - Dynamic status dot inside the connection status pill badge.
- **Haptic Tactile Feedback**:
  - Added subtle tactile vibrations (`KEYBOARD_TAP`) on pull-to-refresh, manual refresh, °C / °F unit toggle, theme switch, and dialog button interactions.
- **Performance, Battery & Radio Optimization**:
  - **Screen-Off Radio Power Awareness**: `autoRefreshRunnable` checks `PowerManager.isInteractive` and suspends network polling when device screen is turned off to prevent Wi-Fi/cellular modem wakeups.
  - **R8 Minification & Resource Shrinking**: Enabled code shrinking and resource optimization on release builds (`isMinifyEnabled = true`, `isShrinkResources = true`).
  - Slashed release APK size by **50% (from ~10 MB down to 5.0 MB)** with faster cold boot and reduced runtime memory footprint.
  - Added ProGuard preservation rules for JSch, BouncyCastle Ed25519, Gson, and AndroidX WorkManager.

---

## [v1.0.0-beta] - 2026-11-02

### Added
- Initial beta release of HotBoi.
- Standalone on-device SFP+ optical telemetry monitor for UniFi Cloud Gateway Fiber (White Bean).
- Direct SSH engine via JSch with BouncyCastle Ed25519 private key authentication.
- Real-time laser optics temperature, CPU Core 0/1 temps, optical Rx/Tx power, laser bias current, and gateway ping latency.
- Automated background WorkManager polling (every 1 hour) with high-priority thermal trip push notifications.
- Local Room / SQLite historical persistence and responsive Chart.js visual dashboard.
- Multi-mode server fallback (macOS, Windows, Linux).
- One-tap °C / °F unit conversion and System / Light / Dark theme modes.
