package com.zlatty.hotboi

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.zlatty.hotboi.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MainActivity: Primary UI controller for HotBoi Universal SFP+ Thermal Telemetry.
 *
 * Architecture & Features:
 * 1. Hybrid Native & WebView UI: Native top-bar controls + Chart.js responsive dashboard.
 * 2. Modern App Splash / Loading Page: Smooth fade-out once initial telemetry is rendered.
 * 3. Multi-Mode Telemetry:
 *    - Direct SSH (BouncyCastle Ed25519 authenticated direct from phone to gateway/switch).
 *    - macOS Server Daemon (BETA).
 *    - Windows 10/11 Server Background Service (BETA).
 *    - Linux Server Daemon for Debian / Ubuntu (BETA).
 *    - Offline Cached Telemetry (Room / SQLite local history).
 * 4. Dual Temperature Units: Instant one-tap conversion between Celsius (°C) and Fahrenheit (°F).
 * 5. Full Theme Engine: System Default, Light Mode, and Dark Mode with dynamic chart styling.
 * 6. High-Priority Push Alerts: Push notification upon crossing thermal trip limit.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private val refreshHandler = Handler(Looper.getMainLooper())
    private var isPageLoaded = false
    private var hasPromptedPingMismatch = false

    companion object {
        private const val LOCAL_URL = "file:///android_asset/dashboard.html"
        private const val AUTO_REFRESH_INTERVAL_MS = 60_000L
        private const val KEY_FIRST_LAUNCH = "first_launch_done"
    }

    /**
     * ActivityResultLauncher for Android 13+ (API 33+) POST_NOTIFICATIONS runtime permission.
     */
    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                Toast.makeText(this, "Push notifications enabled", Toast.LENGTH_SHORT).show()
            }
        }

    /**
     * Periodic foreground auto-refresh runnable executing every 60 seconds.
     */
    private val autoRefreshRunnable = object : Runnable {
        override fun run() {
            triggerDataRefresh(isSilent = true)
            refreshHandler.postDelayed(this, AUTO_REFRESH_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(SshCollector.PREFS_NAME, Context.MODE_PRIVATE)

        // Seed default SSH key if not yet configured
        if (!prefs.contains(SshCollector.KEY_GATEWAY_SSH_KEY)) {
            prefs.edit().putString(SshCollector.KEY_GATEWAY_SSH_KEY, SshCollector.DEFAULT_SSH_KEY).apply()
        }

        checkAndRequestNotificationPermission()
        setupWindowInsets()
        setupBackNavigation()
        setupWebView()
        setupListeners()

        // Load local asset dashboard
        binding.webView.loadUrl(LOCAL_URL)

        // Trigger welcome initial setup dialog on first run
        if (!prefs.getBoolean(KEY_FIRST_LAUNCH, false)) {
            prefs.edit().putBoolean(KEY_FIRST_LAUNCH, true).apply()
            showSettingsDialog(isFirstLaunch = true)
        }
    }

    /**
     * Requests runtime notification permission on Android 13+ devices.
     */
    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /**
     * Handles edge-to-edge system insets (status bar cutout, navigation bar).
     */
    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { _, insets ->
            val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val displayCutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())

            val topInset = maxOf(statusBar.top, displayCutout.top)

            binding.topBar.updatePadding(
                top = topInset + 4,
                left = statusBar.left,
                right = statusBar.right
            )
            binding.webView.updatePadding(
                bottom = navBar.bottom
            )
            insets
        }
    }

    /**
     * Back navigation handler ensuring in-WebView navigation before activity finish.
     */
    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    /**
     * Configures Android WebView with transparent background for adaptive theming.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = binding.webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        binding.webView.setBackgroundColor(Color.TRANSPARENT)

        binding.webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                android.util.Log.d("HotBoiJS", "${consoleMessage?.message()} -- line ${consoleMessage?.lineNumber()}")
                return true
            }
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                isPageLoaded = true
                binding.swipeRefresh.isRefreshing = false
                binding.progressBar.visibility = View.GONE

                injectTelemetryIntoWebView()

                // Smoothly dismiss modern app splash / loading page
                if (binding.layoutLoading.visibility == View.VISIBLE) {
                    binding.layoutLoading.animate()
                        .alpha(0f)
                        .setDuration(400)
                        .withEndAction {
                            binding.layoutLoading.visibility = View.GONE
                        }
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                binding.swipeRefresh.isRefreshing = false
                binding.progressBar.visibility = View.GONE
                if (binding.layoutLoading.visibility == View.VISIBLE) {
                    binding.layoutLoading.visibility = View.GONE
                }
            }
        }
    }

    /**
     * Updates top-bar toggle button label to reflect active temperature unit (°C or °F).
     */
    private fun updateUnitButtonLabel() {
        val current = prefs.getString(SshCollector.KEY_TEMP_UNIT, SshCollector.UNIT_CELSIUS) ?: SshCollector.UNIT_CELSIUS
        binding.btnUnitToggle.text = if (current == SshCollector.UNIT_FAHRENHEIT) "°F" else "°C"
    }

    /**
     * Attaches click listeners for all interactive top-bar controls.
     */
    private fun setupListeners() {
        updateUnitButtonLabel()

        // One-tap quick toggle between Celsius and Fahrenheit
        binding.btnUnitToggle.setOnClickListener {
            val current = prefs.getString(SshCollector.KEY_TEMP_UNIT, SshCollector.UNIT_CELSIUS) ?: SshCollector.UNIT_CELSIUS
            val newUnit = if (current == SshCollector.UNIT_CELSIUS) SshCollector.UNIT_FAHRENHEIT else SshCollector.UNIT_CELSIUS
            prefs.edit().putString(SshCollector.KEY_TEMP_UNIT, newUnit).apply()
            updateUnitButtonLabel()
            injectTelemetryIntoWebView()
            val label = if (newUnit == SshCollector.UNIT_FAHRENHEIT) "Switched to Fahrenheit (°F)" else "Switched to Celsius (°C)"
            Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
        }

        binding.swipeRefresh.setOnRefreshListener {
            triggerDataRefresh(isSilent = false)
        }

        binding.btnRefresh.setOnClickListener {
            binding.swipeRefresh.isRefreshing = true
            triggerDataRefresh(isSilent = false)
        }

        binding.btnSettings.setOnClickListener {
            showSettingsDialog(isFirstLaunch = false)
        }

        binding.btnErrorLog.setOnClickListener {
            showErrorDetailsDialog()
        }

        binding.tvStatus.setOnClickListener {
            showErrorDetailsDialog()
        }
    }

    /**
     * Serializes telemetry bundle from Room/SQLite cache and invokes JS render hook in WebView.
     */
    private fun injectTelemetryIntoWebView() {
        val mode = prefs.getString(SshCollector.KEY_COLLECTION_MODE, "ssh") ?: "ssh"
        val bundleJson = TelemetryRepository.toJsonBundle(this, mode)
        binding.webView.evaluateJavascript("window.renderDashboardWithData($bundleJson);", null)
    }

    /**
     * Triggers telemetry poll across configured mode (Direct SSH, macOS, Windows, Linux, or Offline).
     */
    private fun triggerDataRefresh(isSilent: Boolean) {
        val mode = prefs.getString(SshCollector.KEY_COLLECTION_MODE, SshCollector.MODE_SSH) ?: SshCollector.MODE_SSH

        if (!isSilent) {
            binding.progressBar.visibility = View.VISIBLE
        }

        when (mode) {
            SshCollector.MODE_SSH -> {
                lifecycleScope.launch(Dispatchers.IO) {
                    val host = prefs.getString(SshCollector.KEY_GATEWAY_HOST, SshCollector.DEFAULT_HOST) ?: SshCollector.DEFAULT_HOST
                    val result = SshCollector.collectViaSsh(this@MainActivity)

                    withContext(Dispatchers.Main) {
                        binding.progressBar.visibility = View.GONE
                        binding.swipeRefresh.isRefreshing = false

                        if (result.isSuccess) {
                            val sample = result.getOrThrow()
                            TelemetryRepository.addSample(this@MainActivity, sample, SshCollector.MODE_SSH)
                            NotificationHelper.checkThresholdAndAlert(this@MainActivity, sample)
                            injectTelemetryIntoWebView()

                            binding.btnErrorLog.visibility = View.GONE
                            binding.tvStatus.text = "🟢 Direct ($host)"
                            binding.tvStatus.setTextColor(Color.parseColor("#34D399"))
                        } else {
                            injectTelemetryIntoWebView()
                            binding.btnErrorLog.visibility = View.VISIBLE
                            binding.tvStatus.text = "⚠️ Error (Tap to view)"
                            binding.tvStatus.setTextColor(Color.parseColor("#F87171"))
                        }
                    }
                }
            }
            SshCollector.MODE_MAC_SERVER, SshCollector.MODE_WIN_SERVER, SshCollector.MODE_LINUX_SERVER, SshCollector.MODE_SERVER -> {
                val (defaultUrl, osLabel) = when (mode) {
                    SshCollector.MODE_WIN_SERVER -> Pair(SshCollector.DEFAULT_WIN_SERVER_URL, "Windows (BETA)")
                    SshCollector.MODE_LINUX_SERVER -> Pair(SshCollector.DEFAULT_LINUX_SERVER_URL, "Linux (BETA)")
                    SshCollector.MODE_MAC_SERVER -> Pair(SshCollector.DEFAULT_MAC_SERVER_URL, "macOS (BETA)")
                    else -> Pair(SshCollector.DEFAULT_SERVER_URL, "Server (BETA)")
                }

                val prefKey = when (mode) {
                    SshCollector.MODE_WIN_SERVER -> SshCollector.KEY_WIN_SERVER_URL
                    SshCollector.MODE_LINUX_SERVER -> SshCollector.KEY_LINUX_SERVER_URL
                    SshCollector.MODE_MAC_SERVER -> SshCollector.KEY_MAC_SERVER_URL
                    else -> SshCollector.KEY_SERVER_URL
                }

                val serverUrl = prefs.getString(prefKey, defaultUrl) ?: defaultUrl

                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val base = serverUrl.trimEnd('/')
                        // First attempt to fetch full telemetry history from the server
                        var parsedSamples: List<TelemetrySample>? = null
                        var latestSample: TelemetrySample? = null
                        var serverPing: String? = null

                        try {
                            val historyUrl = URL("$base/api/history")
                            val hConn = (historyUrl.openConnection() as HttpURLConnection).apply {
                                connectTimeout = 4000
                                readTimeout = 4000
                                requestMethod = "GET"
                            }
                            if (hConn.responseCode == 200) {
                                val hJson = hConn.inputStream.bufferedReader().readText()
                                val jsonObject = com.google.gson.JsonParser.parseString(hJson).asJsonObject
                                if (jsonObject.has("samples") && jsonObject.get("samples").isJsonArray) {
                                    val listType = object : com.google.gson.reflect.TypeToken<List<TelemetrySample>>() {}.type
                                    parsedSamples = Gson().fromJson(jsonObject.get("samples"), listType)
                                }
                                if (jsonObject.has("latest") && jsonObject.get("latest").isJsonObject) {
                                    latestSample = Gson().fromJson(jsonObject.get("latest"), TelemetrySample::class.java)
                                }
                                if (jsonObject.has("ping_target")) {
                                    serverPing = jsonObject.get("ping_target").asString
                                } else if (jsonObject.has("pingTarget")) {
                                    serverPing = jsonObject.get("pingTarget").asString
                                }
                            }
                        } catch (_: Exception) {}

                        // Fallback to /api/telemetry if /api/history wasn't returned
                        if (latestSample == null) {
                            val singleUrl = URL("$base/api/telemetry")
                            val sConn = (singleUrl.openConnection() as HttpURLConnection).apply {
                                connectTimeout = 4000
                                readTimeout = 4000
                                requestMethod = "GET"
                            }
                            if (sConn.responseCode == 200) {
                                val sJson = sConn.inputStream.bufferedReader().readText()
                                latestSample = Gson().fromJson(sJson, TelemetrySample::class.java)
                                val jsonObject = com.google.gson.JsonParser.parseString(sJson).asJsonObject
                                if (serverPing == null && jsonObject.has("ping_target")) {
                                    serverPing = jsonObject.get("ping_target").asString
                                }
                            } else {
                                throw Exception("HTTP Error: ${sConn.responseCode} ${sConn.responseMessage}")
                            }
                        }

                        if (parsedSamples != null && parsedSamples.isNotEmpty()) {
                            TelemetryRepository.setSamples(this@MainActivity, parsedSamples, mode)
                        } else if (latestSample != null) {
                            TelemetryRepository.addSample(this@MainActivity, latestSample, mode)
                        }

                        if (latestSample != null) {
                            NotificationHelper.checkThresholdAndAlert(this@MainActivity, latestSample)
                        }

                        // Inspect ping target mismatch and prompt user to synchronize if different
                        val appPing = prefs.getString(SshCollector.KEY_PING_TARGET, "1.1.1.1") ?: "1.1.1.1"
                        if (!hasPromptedPingMismatch && !serverPing.isNullOrBlank() &&
                            !serverPing.contains(appPing) && !appPing.contains(serverPing)) {
                            hasPromptedPingMismatch = true
                            withContext(Dispatchers.Main) {
                                showPingTargetMismatchDialog(base, osLabel, serverPing, appPing)
                            }
                        }

                        withContext(Dispatchers.Main) {
                            binding.progressBar.visibility = View.GONE
                            binding.swipeRefresh.isRefreshing = false
                            binding.btnErrorLog.visibility = View.GONE
                            binding.tvStatus.text = "🟢 $osLabel"
                            binding.tvStatus.setTextColor(Color.parseColor("#38BDF8"))
                            injectTelemetryIntoWebView()
                        }
                    } catch (e: Exception) {
                        ErrorLogManager.logError("$osLabel Error ($serverUrl)", e)
                        withContext(Dispatchers.Main) {
                            binding.progressBar.visibility = View.GONE
                            binding.swipeRefresh.isRefreshing = false
                            binding.btnErrorLog.visibility = View.VISIBLE
                            binding.tvStatus.text = "⚠️ $osLabel Error"
                            binding.tvStatus.setTextColor(Color.parseColor("#F87171"))
                            injectTelemetryIntoWebView()
                        }
                    }
                }
            }
            else -> {
                // Offline mode: zero network calls, read entirely from local room database
                injectTelemetryIntoWebView()
                binding.btnErrorLog.visibility = View.GONE
                binding.tvStatus.text = "💾 Offline (Cached)"
                binding.tvStatus.setTextColor(Color.parseColor("#94A3B8"))
                binding.swipeRefresh.isRefreshing = false
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    /**
     * Prompts the user when a ping target discrepancy is detected between the host server and mobile app,
     * allowing the user to push app config to the server or inherit the server's ping target.
     */
    private fun showPingTargetMismatchDialog(serverBase: String, serverName: String, serverPing: String, appPing: String) {
        if (isFinishing || isDestroyed) return

        AlertDialog.Builder(this)
            .setTitle("Ping Target Discrepancy")
            .setMessage(
                "Host Collector ($serverName) is currently pinging:\n👉 $serverPing\n\n" +
                "HotBoi on this device is configured for:\n👉 $appPing\n\n" +
                "Would you like to push the app's ping configuration to the host server, or inherit the server's ping target?"
            )
            .setPositiveButton("Push to Server ($appPing)") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val url = URL("$serverBase/api/config")
                        val conn = (url.openConnection() as HttpURLConnection).apply {
                            requestMethod = "POST"
                            doOutput = true
                            connectTimeout = 4000
                            readTimeout = 4000
                            setRequestProperty("Content-Type", "application/json")
                        }
                        val json = org.json.JSONObject().apply {
                            put("ping_target", appPing)
                        }.toString()
                        conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                        val code = conn.responseCode
                        withContext(Dispatchers.Main) {
                            if (code in 200..299) {
                                Toast.makeText(this@MainActivity, "✅ Successfully pushed $appPing to $serverName", Toast.LENGTH_LONG).show()
                                triggerDataRefresh(isSilent = true)
                            } else {
                                Toast.makeText(this@MainActivity, "⚠️ Server returned code $code", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Push failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("Inherit from Server ($serverPing)") { _, _ ->
                prefs.edit()
                    .putString(SshCollector.KEY_PING_TARGET, serverPing)
                    .putString(SshCollector.KEY_PING_TARGET_LABEL, serverPing)
                    .apply()
                injectTelemetryIntoWebView()
                Toast.makeText(this@MainActivity, "✅ Inherited $serverPing from $serverName", Toast.LENGTH_LONG).show()
            }
            .setNeutralButton("Keep Separate", null)
            .show()
    }

    /**
     * Displays system diagnostic modal with copyable stack trace, active system reflection, and clear capability.
     */
    private fun showErrorDetailsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_error_details, null)
        val tvActiveMode = dialogView.findViewById<TextView>(R.id.tvActiveSystemMode)
        val tvSummary = dialogView.findViewById<TextView>(R.id.tvErrorSummary)
        val tvDetails = dialogView.findViewById<TextView>(R.id.tvErrorDetails)
        val btnClear = dialogView.findViewById<Button>(R.id.btnClearError)
        val btnCopy = dialogView.findViewById<Button>(R.id.btnCopyError)
        val btnRetry = dialogView.findViewById<Button>(R.id.btnRetryNow)

        // Show the actual currently active collection mode & endpoint
        val currentMode = prefs.getString(SshCollector.KEY_COLLECTION_MODE, SshCollector.MODE_SSH)
        val modeText = when (currentMode) {
            SshCollector.MODE_MAC_SERVER, SshCollector.MODE_SERVER -> {
                val url = prefs.getString(SshCollector.KEY_MAC_SERVER_URL, SshCollector.DEFAULT_MAC_SERVER_URL)
                "Active System: 🍏 macOS Server ($url)"
            }
            SshCollector.MODE_WIN_SERVER -> {
                val url = prefs.getString(SshCollector.KEY_WIN_SERVER_URL, SshCollector.DEFAULT_WIN_SERVER_URL)
                "Active System: 🪟 Windows Server ($url)"
            }
            SshCollector.MODE_LINUX_SERVER -> {
                val url = prefs.getString(SshCollector.KEY_LINUX_SERVER_URL, SshCollector.DEFAULT_LINUX_SERVER_URL)
                "Active System: 🐧 Linux Server ($url)"
            }
            SshCollector.MODE_OFFLINE -> "Active System: 💾 Offline Cache Mode"
            else -> {
                val host = prefs.getString(SshCollector.KEY_GATEWAY_HOST, SshCollector.DEFAULT_HOST)
                val port = prefs.getInt(SshCollector.KEY_GATEWAY_PORT, SshCollector.DEFAULT_PORT)
                "Active System: ⚡ Direct SSH ($host:$port)"
            }
        }
        tvActiveMode.text = modeText

        val err = ErrorLogManager.lastError
        if (err != null) {
            tvSummary.text = "[${err.timestamp}] ${err.summary}"
            tvDetails.text = "${err.details}\n\n--- Stacktrace ---\n${err.stackTrace}"
        } else {
            tvSummary.text = "No active errors recorded."
            tvDetails.text = "All systems operational. Telemetry engine active."
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setNegativeButton("Close", null)
            .create()

        btnClear.setOnClickListener {
            ErrorLogManager.clearErrors()
            binding.btnErrorLog.visibility = View.GONE
            tvSummary.text = "Diagnostics cleared."
            tvDetails.text = "No active errors recorded. All systems operational."
            Toast.makeText(this, "Cleared diagnostics log", Toast.LENGTH_SHORT).show()
        }

        btnCopy.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val fullText = "${tvActiveMode.text}\n${tvSummary.text}\n\n${tvDetails.text}"
            val clip = ClipData.newPlainText("HotBoi Diagnostics Log", fullText)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Copied error diagnostics to clipboard", Toast.LENGTH_SHORT).show()
        }

        btnRetry.setOnClickListener {
            dialog.dismiss()
            binding.swipeRefresh.isRefreshing = true
            triggerDataRefresh(isSilent = false)
        }

        dialog.show()
    }

    /**
     * Opens comprehensive setup & settings dialog with real-time unit conversion and theme options.
     */
    private fun showSettingsDialog(isFirstLaunch: Boolean) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)

        val etDeviceName = dialogView.findViewById<EditText>(R.id.etDeviceName)
        val spSfp = dialogView.findViewById<Spinner>(R.id.spSfpInterface)
        val etCustomSfp = dialogView.findViewById<EditText>(R.id.etCustomSfp)

        val rgMode = dialogView.findViewById<RadioGroup>(R.id.rgCollectionMethod)
        val rbSsh = dialogView.findViewById<RadioButton>(R.id.rbModeDirectSsh)
        val rbMacServer = dialogView.findViewById<RadioButton>(R.id.rbModeMacServer)
        val rbWinServer = dialogView.findViewById<RadioButton>(R.id.rbModeWinServer)
        val rbLinuxServer = dialogView.findViewById<RadioButton>(R.id.rbModeLinuxServer)
        val rbOffline = dialogView.findViewById<RadioButton>(R.id.rbModeOffline)

        val sectionSsh = dialogView.findViewById<LinearLayout>(R.id.sectionSshSettings)
        val sectionServer = dialogView.findViewById<LinearLayout>(R.id.sectionServerSettings)
        val tvServerTitle = dialogView.findViewById<TextView>(R.id.tvServerSectionTitle)
        val tvServerBetaNote = dialogView.findViewById<TextView>(R.id.tvServerBetaNote)

        val etHost = dialogView.findViewById<EditText>(R.id.etHost)
        val etPort = dialogView.findViewById<EditText>(R.id.etPort)
        val etUser = dialogView.findViewById<EditText>(R.id.etUser)
        val etPassword = dialogView.findViewById<EditText>(R.id.etPassword)
        val etSshKey = dialogView.findViewById<EditText>(R.id.etSshKey)
        val btnLoadMacKey = dialogView.findViewById<Button>(R.id.btnLoadMacKey)
        val spPing = dialogView.findViewById<Spinner>(R.id.spPingTarget)
        val etCustomPing = dialogView.findViewById<EditText>(R.id.etCustomPingTarget)
        val btnTest = dialogView.findViewById<Button>(R.id.btnTestConnection)

        val etServerUrl = dialogView.findViewById<EditText>(R.id.etServerUrl)
        val cbBg = dialogView.findViewById<CheckBox>(R.id.cbBackgroundSync)
        val spInterval = dialogView.findViewById<Spinner>(R.id.spInterval)

        val etThreshold = dialogView.findViewById<EditText>(R.id.etThreshold)
        val tvThresholdUnit = dialogView.findViewById<TextView>(R.id.tvThresholdUnit)
        val tvThresholdConverted = dialogView.findViewById<TextView>(R.id.tvThresholdConverted)
        val btnThresholdInfo = dialogView.findViewById<TextView>(R.id.btnThresholdInfo)
        val btnTestNotification = dialogView.findViewById<TextView>(R.id.btnTestNotification)

        val rgTempUnit = dialogView.findViewById<RadioGroup>(R.id.rgTempUnit)
        val rbUnitCelsius = dialogView.findViewById<RadioButton>(R.id.rbUnitCelsius)
        val rbUnitFahrenheit = dialogView.findViewById<RadioButton>(R.id.rbUnitFahrenheit)

        val rgThemeMode = dialogView.findViewById<RadioGroup>(R.id.rgThemeMode)
        val rbThemeSystem = dialogView.findViewById<RadioButton>(R.id.rbThemeSystem)
        val rbThemeLight = dialogView.findViewById<RadioButton>(R.id.rbThemeLight)
        val rbThemeDark = dialogView.findViewById<RadioButton>(R.id.rbThemeDark)

        // 1. Hardware & Universal SFP Interface Setup
        etDeviceName.setText(prefs.getString(SshCollector.KEY_DEVICE_NAME, SshCollector.DEFAULT_DEVICE_NAME))

        val sfpOptions = listOf(
            "Port 7 (eth6) — UCG-Fiber SFP+",
            "Port 8 (eth7)",
            "Port 10 (eth9) — UDM-Pro / SE SFP+",
            "Port 11 (eth10) — UDM-Pro / SE SFP+",
            "Custom Interface..."
        )
        val sfpAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, sfpOptions)
        spSfp.adapter = sfpAdapter

        val savedSfp = prefs.getString(SshCollector.KEY_SFP_INTERFACE, SshCollector.DEFAULT_SFP_INTERFACE) ?: "eth6"
        when (savedSfp) {
            "eth6" -> spSfp.setSelection(0)
            "eth7" -> spSfp.setSelection(1)
            "eth9" -> spSfp.setSelection(2)
            "eth10" -> spSfp.setSelection(3)
            else -> {
                spSfp.setSelection(4)
                etCustomSfp.visibility = View.VISIBLE
                etCustomSfp.setText(savedSfp)
            }
        }

        spSfp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                etCustomSfp.visibility = if (position == 4) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 2. Collection Mode Selection Prepopulation
        val currentMode = prefs.getString(SshCollector.KEY_COLLECTION_MODE, SshCollector.MODE_SSH) ?: SshCollector.MODE_SSH
        val macUrl = prefs.getString(SshCollector.KEY_MAC_SERVER_URL, SshCollector.DEFAULT_MAC_SERVER_URL) ?: SshCollector.DEFAULT_MAC_SERVER_URL
        val winUrl = prefs.getString(SshCollector.KEY_WIN_SERVER_URL, SshCollector.DEFAULT_WIN_SERVER_URL) ?: SshCollector.DEFAULT_WIN_SERVER_URL
        val linuxUrl = prefs.getString(SshCollector.KEY_LINUX_SERVER_URL, SshCollector.DEFAULT_LINUX_SERVER_URL) ?: SshCollector.DEFAULT_LINUX_SERVER_URL

        fun configureServerSection(title: String, note: String, url: String) {
            sectionSsh.visibility = View.GONE
            sectionServer.visibility = View.VISIBLE
            tvServerTitle.text = title
            tvServerBetaNote.text = note
            etServerUrl.setText(url)
        }

        when (currentMode) {
            SshCollector.MODE_MAC_SERVER, "server" -> {
                rbMacServer.isChecked = true
                configureServerSection(
                    "MACOS SERVER ENDPOINT (BETA)",
                    "🍏 BETA FEATURE: 24/7 background collector running on your Mac. Future premium feature.",
                    macUrl
                )
            }
            SshCollector.MODE_WIN_SERVER -> {
                rbWinServer.isChecked = true
                configureServerSection(
                    "WINDOWS SERVER ENDPOINT (BETA)",
                    "🪟 BETA FEATURE: 24/7 background service running on Windows 10/11. Future premium feature.",
                    winUrl
                )
            }
            SshCollector.MODE_LINUX_SERVER -> {
                rbLinuxServer.isChecked = true
                configureServerSection(
                    "LINUX SERVER ENDPOINT (BETA)",
                    "🐧 BETA FEATURE: 24/7 background systemd daemon on Debian/Ubuntu. Future premium feature.",
                    linuxUrl
                )
            }
            SshCollector.MODE_OFFLINE -> {
                rbOffline.isChecked = true
                sectionSsh.visibility = View.GONE
                sectionServer.visibility = View.GONE
            }
            else -> {
                rbSsh.isChecked = true
                sectionSsh.visibility = View.VISIBLE
                sectionServer.visibility = View.GONE
            }
        }

        rgMode.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rbModeMacServer -> configureServerSection(
                    "MACOS SERVER ENDPOINT (BETA)",
                    "🍏 BETA FEATURE: 24/7 background collector running on your Mac. Future premium feature.",
                    prefs.getString(SshCollector.KEY_MAC_SERVER_URL, SshCollector.DEFAULT_MAC_SERVER_URL) ?: SshCollector.DEFAULT_MAC_SERVER_URL
                )
                R.id.rbModeWinServer -> configureServerSection(
                    "WINDOWS SERVER ENDPOINT (BETA)",
                    "🪟 BETA FEATURE: 24/7 background service running on Windows 10/11. Future premium feature.",
                    prefs.getString(SshCollector.KEY_WIN_SERVER_URL, SshCollector.DEFAULT_WIN_SERVER_URL) ?: SshCollector.DEFAULT_WIN_SERVER_URL
                )
                R.id.rbModeLinuxServer -> configureServerSection(
                    "LINUX SERVER ENDPOINT (BETA)",
                    "🐧 BETA FEATURE: 24/7 background systemd daemon on Debian/Ubuntu. Future premium feature.",
                    prefs.getString(SshCollector.KEY_LINUX_SERVER_URL, SshCollector.DEFAULT_LINUX_SERVER_URL) ?: SshCollector.DEFAULT_LINUX_SERVER_URL
                )
                R.id.rbModeOffline -> {
                    sectionSsh.visibility = View.GONE
                    sectionServer.visibility = View.GONE
                }
                else -> {
                    sectionSsh.visibility = View.VISIBLE
                    sectionServer.visibility = View.GONE
                }
            }
        }

        // 3. Ping Target Dropdown Prepopulation
        val pingOptions = listOf(
            "Cloudflare (1.1.1.1)",
            "Google (8.8.8.8)",
            "Quad9 (9.9.9.9)",
            "Custom..."
        )
        val pingAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, pingOptions)
        spPing.adapter = pingAdapter

        val savedTarget = prefs.getString(SshCollector.KEY_PING_TARGET, "1.1.1.1") ?: "1.1.1.1"
        when (savedTarget) {
            "1.1.1.1" -> spPing.setSelection(0)
            "8.8.8.8" -> spPing.setSelection(1)
            "9.9.9.9" -> spPing.setSelection(2)
            else -> {
                spPing.setSelection(3)
                etCustomPing.visibility = View.VISIBLE
                etCustomPing.setText(savedTarget)
            }
        }

        spPing.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                etCustomPing.visibility = if (position == 3) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 4. Background Interval Dropdown
        val intervalOptions = listOf(
            "Every 15 minutes",
            "Every 30 minutes",
            "Every 1 hour (Default)",
            "Every 2 hours"
        )
        val intervalAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, intervalOptions)
        spInterval.adapter = intervalAdapter

        val savedInterval = prefs.getInt(SshCollector.KEY_BG_INTERVAL_MIN, 60)
        when (savedInterval) {
            15 -> spInterval.setSelection(0)
            30 -> spInterval.setSelection(1)
            120 -> spInterval.setSelection(3)
            else -> spInterval.setSelection(2)
        }

        // Prepopulate text fields
        etHost.setText(prefs.getString(SshCollector.KEY_GATEWAY_HOST, SshCollector.DEFAULT_HOST))
        etPort.setText(prefs.getInt(SshCollector.KEY_GATEWAY_PORT, SshCollector.DEFAULT_PORT).toString())
        etUser.setText(prefs.getString(SshCollector.KEY_GATEWAY_USER, SshCollector.DEFAULT_USER))
        etPassword.setText(prefs.getString(SshCollector.KEY_GATEWAY_PASSWORD, ""))
        etSshKey.setText(prefs.getString(SshCollector.KEY_GATEWAY_SSH_KEY, SshCollector.DEFAULT_SSH_KEY))
        cbBg.isChecked = prefs.getBoolean(SshCollector.KEY_BG_ENABLED, true)

        // 5. Units & Dynamic Real-Time Thermal Threshold Conversion
        val currentUnit = prefs.getString(SshCollector.KEY_TEMP_UNIT, SshCollector.UNIT_CELSIUS) ?: SshCollector.UNIT_CELSIUS
        val savedThresholdC = prefs.getFloat(SshCollector.KEY_ALERT_THRESHOLD, SshCollector.DEFAULT_ALERT_THRESHOLD.toFloat())

        fun updateThresholdConversion(inputVal: Float?, isFahrenheit: Boolean) {
            if (inputVal == null) {
                tvThresholdConverted.text = ""
                return
            }
            if (isFahrenheit) {
                val c = (inputVal - 32f) * 5f / 9f
                tvThresholdConverted.text = String.format(Locale.US, "(≈ %.1f °C)", c)
            } else {
                val f = (inputVal * 9f / 5f) + 32f
                tvThresholdConverted.text = String.format(Locale.US, "(≈ %.1f °F)", f)
            }
        }

        if (currentUnit == SshCollector.UNIT_FAHRENHEIT) {
            rbUnitFahrenheit.isChecked = true
            val initialF = (savedThresholdC * 9f / 5f) + 32f
            etThreshold.setText(String.format(Locale.US, "%.1f", initialF))
            tvThresholdUnit.text = "°F"
            updateThresholdConversion(initialF, isFahrenheit = true)
        } else {
            rbUnitCelsius.isChecked = true
            etThreshold.setText(String.format(Locale.US, "%.1f", savedThresholdC))
            tvThresholdUnit.text = "°C"
            updateThresholdConversion(savedThresholdC, isFahrenheit = false)
        }

        // Listener for temperature unit changes within settings dialog
        rgTempUnit.setOnCheckedChangeListener { _, checkedId ->
            val isF = checkedId == R.id.rbUnitFahrenheit
            val curVal = etThreshold.text.toString().trim().toFloatOrNull()
            if (curVal != null) {
                if (isF) {
                    val f = (curVal * 9f / 5f) + 32f
                    etThreshold.setText(String.format(Locale.US, "%.1f", f))
                    tvThresholdUnit.text = "°F"
                    updateThresholdConversion(f, true)
                } else {
                    val c = (curVal - 32f) * 5f / 9f
                    etThreshold.setText(String.format(Locale.US, "%.1f", c))
                    tvThresholdUnit.text = "°C"
                    updateThresholdConversion(c, false)
                }
            } else {
                tvThresholdUnit.text = if (isF) "°F" else "°C"
            }
        }

        // Live text change listener updating secondary converted unit
        etThreshold.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val v = s?.toString()?.trim()?.toFloatOrNull()
                updateThresholdConversion(v, rbUnitFahrenheit.isChecked)
            }
        })

        // 6. Theme Prepopulation
        val currentTheme = prefs.getString(SshCollector.KEY_THEME_MODE, SshCollector.THEME_SYSTEM) ?: SshCollector.THEME_SYSTEM
        when (currentTheme) {
            SshCollector.THEME_LIGHT -> rbThemeLight.isChecked = true
            SshCollector.THEME_DARK -> rbThemeDark.isChecked = true
            else -> rbThemeSystem.isChecked = true
        }

        // Test Push Notification Button
        btnTestNotification.setOnClickListener {
            checkAndRequestNotificationPermission()
            val entered = etThreshold.text.toString().trim().toFloatOrNull() ?: SshCollector.DEFAULT_ALERT_THRESHOLD.toFloat()
            val thresholdC = if (rbUnitFahrenheit.isChecked) (entered - 32f) * 5f / 9f else entered
            val sample = TelemetryRepository.getLatestSample(this) ?: TelemetrySample(
                timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()),
                opticTemp = 42.5,
                cpu0Temp = 55.0,
                cpu1Temp = 54.0,
                ploam = "O5.1",
                rxPower = -22.1,
                txPower = 4.5,
                biasMa = 14.0,
                pingMs = 7.0
            )
            NotificationHelper.sendThermalAlert(this, sample, thresholdC.toDouble(), isTest = true)
            Toast.makeText(this, "Push notification sent! Check your notification shade.", Toast.LENGTH_SHORT).show()
        }

        // Circular Info (i) button for notification architecture breakdown
        btnThresholdInfo.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Push Notifications Architecture")
                .setMessage("ℹ️ Direct SSH Mode:\nOn-device thermal trip alerts trigger directly on this phone via background workers.\n\n🍏 macOS, 🪟 Windows, & 🐧 Linux Server Modes:\nTelemetry collection and critical alerts run 24/7 on your dedicated host service even when your phone is asleep, closed, or off-network.")
                .setPositiveButton("Got It", null)
                .show()
        }

        // Load Authorized Key button
        btnLoadMacKey.setOnClickListener {
            etSshKey.setText(SshCollector.DEFAULT_SSH_KEY)
            Toast.makeText(this, "Loaded authorized UniFi SSH key", Toast.LENGTH_SHORT).show()
        }

        // Test Connection Button
        btnTest.setOnClickListener {
            val host = etHost.text.toString().trim().ifEmpty { SshCollector.DEFAULT_HOST }
            val port = etPort.text.toString().trim().toIntOrNull() ?: SshCollector.DEFAULT_PORT
            val user = etUser.text.toString().trim().ifEmpty { SshCollector.DEFAULT_USER }
            val pass = etPassword.text.toString()
            val key = etSshKey.text.toString().trim()

            btnTest.isEnabled = false
            btnTest.text = "Testing..."

            prefs.edit()
                .putString(SshCollector.KEY_GATEWAY_HOST, host)
                .putInt(SshCollector.KEY_GATEWAY_PORT, port)
                .putString(SshCollector.KEY_GATEWAY_USER, user)
                .putString(SshCollector.KEY_GATEWAY_PASSWORD, pass)
                .putString(SshCollector.KEY_GATEWAY_SSH_KEY, key)
                .apply()

            lifecycleScope.launch(Dispatchers.IO) {
                val res = SshCollector.testConnection(this@MainActivity)
                withContext(Dispatchers.Main) {
                    btnTest.isEnabled = true
                    btnTest.text = "🔍 Test Connection"
                    if (res.isSuccess) {
                        Toast.makeText(this@MainActivity, "✅ " + res.getOrThrow(), Toast.LENGTH_LONG).show()
                    } else {
                        showErrorDetailsDialog()
                    }
                }
            }
        }

        val title = if (isFirstLaunch) "Welcome to HotBoi (Initial Setup)" else getString(R.string.settings)

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(dialogView)
            .setPositiveButton(R.string.save) { _, _ ->
                val chosenMode = when {
                    rbMacServer.isChecked -> SshCollector.MODE_MAC_SERVER
                    rbWinServer.isChecked -> SshCollector.MODE_WIN_SERVER
                    rbLinuxServer.isChecked -> SshCollector.MODE_LINUX_SERVER
                    rbOffline.isChecked -> SshCollector.MODE_OFFLINE
                    else -> SshCollector.MODE_SSH
                }

                val devName = etDeviceName.text.toString().trim().ifEmpty { SshCollector.DEFAULT_DEVICE_NAME }
                val (chosenIface, ifaceLabel) = when (spSfp.selectedItemPosition) {
                    0 -> Pair("eth6", "Port 7 SFP+")
                    1 -> Pair("eth7", "Port 8 SFP+")
                    2 -> Pair("eth9", "Port 10 SFP+")
                    3 -> Pair("eth10", "Port 11 SFP+")
                    else -> {
                        val c = etCustomSfp.text.toString().trim().ifEmpty { "eth6" }
                        Pair(c, "$c SFP+")
                    }
                }

                val host = etHost.text.toString().trim().ifEmpty { SshCollector.DEFAULT_HOST }
                val port = etPort.text.toString().trim().toIntOrNull() ?: SshCollector.DEFAULT_PORT
                val user = etUser.text.toString().trim().ifEmpty { SshCollector.DEFAULT_USER }
                val pass = etPassword.text.toString()
                val key = etSshKey.text.toString().trim()
                val sUrl = etServerUrl.text.toString().trim().ifEmpty { SshCollector.DEFAULT_SERVER_URL }

                // Always normalize saved threshold to Celsius for consistent backend storage
                val enteredThreshold = etThreshold.text.toString().trim().toFloatOrNull() ?: SshCollector.DEFAULT_ALERT_THRESHOLD.toFloat()
                val thresholdC = if (rbUnitFahrenheit.isChecked) {
                    (enteredThreshold - 32f) * 5f / 9f
                } else {
                    enteredThreshold
                }

                val (chosenTarget, targetLabel) = when (spPing.selectedItemPosition) {
                    0 -> Pair("1.1.1.1", "1.1.1.1 (Cloudflare)")
                    1 -> Pair("8.8.8.8", "8.8.8.8 (Google)")
                    2 -> Pair("9.9.9.9", "9.9.9.9 (Quad9)")
                    else -> {
                        val custom = etCustomPing.text.toString().trim()
                        if (custom.isNotEmpty()) Pair(custom, custom) else Pair("1.1.1.1", "1.1.1.1 (Cloudflare)")
                    }
                }

                val intervalMin = when (spInterval.selectedItemPosition) {
                    0 -> 15
                    1 -> 30
                    3 -> 120
                    else -> 60
                }

                val chosenUnit = if (rbUnitFahrenheit.isChecked) SshCollector.UNIT_FAHRENHEIT else SshCollector.UNIT_CELSIUS
                val chosenTheme = when {
                    rbThemeLight.isChecked -> SshCollector.THEME_LIGHT
                    rbThemeDark.isChecked -> SshCollector.THEME_DARK
                    else -> SshCollector.THEME_SYSTEM
                }

                val oldTheme = prefs.getString(SshCollector.KEY_THEME_MODE, SshCollector.THEME_SYSTEM)

                prefs.edit()
                    .putString(SshCollector.KEY_DEVICE_NAME, devName)
                    .putString(SshCollector.KEY_SFP_INTERFACE, chosenIface)
                    .putString(SshCollector.KEY_SFP_PORT_LABEL, ifaceLabel)
                    .putString(SshCollector.KEY_COLLECTION_MODE, chosenMode)
                    .putString(SshCollector.KEY_GATEWAY_HOST, host)
                    .putInt(SshCollector.KEY_GATEWAY_PORT, port)
                    .putString(SshCollector.KEY_GATEWAY_USER, user)
                    .putString(SshCollector.KEY_GATEWAY_PASSWORD, pass)
                    .putString(SshCollector.KEY_GATEWAY_SSH_KEY, key)
                    .putString(SshCollector.KEY_SERVER_URL, sUrl)
                    .apply {
                        when (chosenMode) {
                            SshCollector.MODE_WIN_SERVER -> putString(SshCollector.KEY_WIN_SERVER_URL, sUrl)
                            SshCollector.MODE_LINUX_SERVER -> putString(SshCollector.KEY_LINUX_SERVER_URL, sUrl)
                            SshCollector.MODE_MAC_SERVER -> putString(SshCollector.KEY_MAC_SERVER_URL, sUrl)
                        }
                    }
                    .putString(SshCollector.KEY_PING_TARGET, chosenTarget)
                    .putString(SshCollector.KEY_PING_TARGET_LABEL, targetLabel)
                    .putBoolean(SshCollector.KEY_BG_ENABLED, cbBg.isChecked)
                    .putInt(SshCollector.KEY_BG_INTERVAL_MIN, intervalMin)
                    .putFloat(SshCollector.KEY_ALERT_THRESHOLD, thresholdC)
                    .putString(SshCollector.KEY_TEMP_UNIT, chosenUnit)
                    .putString(SshCollector.KEY_THEME_MODE, chosenTheme)
                    .apply()

                updateUnitButtonLabel()
                HotBoiApp.rescheduleBackgroundWork(this)

                if (oldTheme != chosenTheme) {
                    when (chosenTheme) {
                        SshCollector.THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                        SshCollector.THEME_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                        else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                    }
                }

                Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()

                hasPromptedPingMismatch = false
                injectTelemetryIntoWebView()
                triggerDataRefresh(isSilent = false)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        updateUnitButtonLabel()
        refreshHandler.postDelayed(autoRefreshRunnable, AUTO_REFRESH_INTERVAL_MS)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(autoRefreshRunnable)
    }
}
