# HotBoi — XGS-PON SFP+ Thermal Monitor 
Standalone Native Android companion application and telemetry monitor for your UniFi Cloud Gateway (White Bean) and XGS-PON SFP+ optical stick transceiver.

---

## ⚡ Self-Contained In-App Architecture

HotBoi runs **100% on your Android phone** without requiring a computer running in the middle:

1. **Immediate Collection on App Launch & Pull-to-Refresh**:
   - Directly SSHes from your phone to your UniFi Gateway (`192.168.0.254:22`) over home Wi-Fi using the embedded `JSch` engine.
   - Collects laser optics temp, CPU Core 0/1 temps, Rx/Tx optical power, laser bias current, and Cloudflare ping latency in ~1.2s.
   - Saves to the local on-device database and dynamically animates the dashboard cards and Chart.js graphs.

2. **Automated Background Collection (Every 1 Hour)**:
   - Uses Android's official **`WorkManager`** with Wi-Fi constraints (`NetworkType.UNMETERED`).
   - Every hour, if your phone is on Wi-Fi, it polls the gateway in the background and logs the reading into your local history.
   - **Thermal Trip Alerts**: If temperatures exceed the configured safe threshold (default: 80°C), HotBoi fires a high-priority Android system notification.

3. **Remote Server Mode (Optional Fallback)**:
   - Can optionally point to a remote server URL if you want continuous logging when away from home.

---

## 📱 Installing on Your Phone

The APK has been compiled and is ready at:  
👉 `HotBoi-debug.apk` (`/Users/lappy/Documents/Antigravity/HotBoi/HotBoi-debug.apk`)

### Option A: USB Sideload (Fastest if ADB connected)
```bash
cd /Users/lappy/Documents/Antigravity/HotBoi
adb install HotBoi-debug.apk
```

### Option B: One-Tap Download from Your Phone Browser
Start the local server temporarily on your Mac:
```bash
python3 server/hotboi_server.py
```
On your phone (connected to the same Wi-Fi), open Chrome and navigate to:  
👉 **`http://192.168.0.134:8080/hotboi.apk`**

### Option C: Share File
Send `HotBoi-debug.apk` via Quick Share, Google Drive, or email to your phone and install.

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
   - **Trip Alert Threshold**: `80°C`
4. Tap **Save**. HotBoi will immediately connect to your UniFi gateway and display live telemetry!

---

## 🏪 Google Play Store Publishing

- **Package Name**: `com.zlatty.hotboi`
- **Target SDK**: `35` (Android 15)
- **Min SDK**: `26` (Android 8.0)

To generate the production bundle for Google Play Console:
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew bundleRelease
```
Output: `app/build/outputs/bundle/release/app-release.aab`
