package com.medusko.hotboi

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
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
import com.medusko.hotboi.databinding.ActivityMainBinding
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
 * 5. Full Theme Engine: Instant top-bar toggle between Light Mode and Dark Mode.
 * 6. High-Priority Push Alerts: Push notification upon crossing thermal trip limit.
 * 7. About HotBoi Diagnostic & Information Screen matching Lagatron and Keyboardy design systems.
 * 8. Screen-Off & Radio Power Optimizations: Automatically skips background polling when screen is sleeping.
 * 9. Physical tactile haptic feedback across interactive navigation actions.
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
     * Screen & radio power aware: Skips network polling when device screen is turned off.
     */
    private val autoRefreshRunnable = object : Runnable {
        override fun run() {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isScreenActive = powerManager?.isInteractive ?: true
            if (isScreenActive) {
                triggerDataRefresh(isSilent = true)
            }
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
        updateThemeIcon()

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
     * Converts density-independent pixels to device pixels.
     */
    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    /**
     * Creates a rounded corner GradientDrawable with optional border stroke.
     */
    private fun createRoundedDrawable(
        fillColor: Int,
        cornerRadiusPx: Float,
        strokeWidthPx: Int = 0,
        strokeColor: Int = Color.TRANSPARENT
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            cornerRadius = cornerRadiusPx
            if (strokeWidthPx > 0) {
                setStroke(strokeWidthPx, strokeColor)
            }
        }
    }

    /**
     * Determines whether dark / night mode is actively rendered.
     */
    private fun isNightModeActive(): Boolean {
        val themeMode = prefs.getString(SshCollector.KEY_THEME_MODE, SshCollector.THEME_SYSTEM)
        return when (themeMode) {
            SshCollector.THEME_DARK -> true
            SshCollector.THEME_LIGHT -> false
            else -> {
                val nightFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                nightFlags == Configuration.UI_MODE_NIGHT_YES
            }
        }
    }

    /**
     * Updates top-bar theme toggle button icon to reflect current UI state (Sun in Dark, Moon in Light).
     */
    private fun updateThemeIcon() {
        val isNight = isNightModeActive()
        binding.ivThemeIcon.setImageResource(
            if (isNight) R.drawable.ic_light_mode else R.drawable.ic_dark_mode
        )
    }

    /**
     * 1-Tap quick toggle between Dark Mode and Light Mode with instant visual feedback.
     */
    private fun toggleTheme() {
        val isNight = isNightModeActive()
        val newTheme = if (isNight) SshCollector.THEME_LIGHT else SshCollector.THEME_DARK
        prefs.edit().putString(SshCollector.KEY_THEME_MODE, newTheme).apply()

        when (newTheme) {
            SshCollector.THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            SshCollector.THEME_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        }
        updateThemeIcon()
        injectTelemetryIntoWebView()
        Toast.makeText(
            this,
            if (newTheme == SshCollector.THEME_DARK) "Dark Mode activated" else "Light Mode activated",
            Toast.LENGTH_SHORT
        ).show()
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
        updateThemeIcon()

        // One-tap quick toggle between Celsius and Fahrenheit
        binding.btnUnitToggle.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            val current = prefs.getString(SshCollector.KEY_TEMP_UNIT, SshCollector.UNIT_CELSIUS) ?: SshCollector.UNIT_CELSIUS
            val newUnit = if (current == SshCollector.UNIT_CELSIUS) SshCollector.UNIT_FAHRENHEIT else SshCollector.UNIT_CELSIUS
            prefs.edit().putString(SshCollector.KEY_TEMP_UNIT, newUnit).apply()
            updateUnitButtonLabel()
            injectTelemetryIntoWebView()
            val label = if (newUnit == SshCollector.UNIT_FAHRENHEIT) "Switched to Fahrenheit (°F)" else "Switched to Celsius (°C)"
            Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
        }

        // About HotBoi Dialog trigger (parity with Keyboardy & Lagatron)
        binding.btnAbout.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            showAboutHotBoiDialog()
        }

        // 1-Tap quick Light / Dark Theme Toggle (Sun in Dark, Moon in Light)
        binding.btnThemeToggle.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            toggleTheme()
        }

        binding.swipeRefresh.setOnRefreshListener {
            binding.swipeRefresh.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            triggerDataRefresh(isSilent = false)
        }

        binding.btnRefresh.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            binding.swipeRefresh.isRefreshing = true
            triggerDataRefresh(isSilent = false)
        }

        binding.btnSettings.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            showSettingsDialog(isFirstLaunch = false)
        }

        binding.btnErrorLog.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            showErrorDetailsDialog()
        }

        binding.tvStatus.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
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
                            injectTelemetryIntoWebView()
                            NotificationHelper.checkThresholdAndAlert(this@MainActivity, sample)

                            binding.btnErrorLog.visibility = View.GONE
                            binding.tvStatus.text = "⚡ Direct SSH"
                            binding.tvStatus.setTextColor(Color.parseColor("#34D399"))
                        } else {
                            val err = result.exceptionOrNull()
                            val msg = err?.message ?: "Unknown SSH Error"
                            ErrorLogManager.logError("SSH Direct Probe ($host)", err ?: Exception(msg))

                            binding.btnErrorLog.visibility = View.VISIBLE
                            binding.tvStatus.text = "Error (SSH)"
                            binding.tvStatus.setTextColor(Color.parseColor("#EF4444"))

                            if (!isSilent) {
                                showErrorDetailsDialog()
                            }
                        }
                    }
                }
            }

            SshCollector.MODE_MAC_SERVER, SshCollector.MODE_WIN_SERVER, SshCollector.MODE_LINUX_SERVER, SshCollector.MODE_SERVER -> {
                val serverName = when (mode) {
                    SshCollector.MODE_MAC_SERVER -> "macOS Server"
                    SshCollector.MODE_WIN_SERVER -> "Windows Server"
                    SshCollector.MODE_LINUX_SERVER -> "Linux Server"
                    else -> "Server"
                }
                val serverIcon = when (mode) {
                    SshCollector.MODE_MAC_SERVER -> "🍏"
                    SshCollector.MODE_WIN_SERVER -> "🪟"
                    SshCollector.MODE_LINUX_SERVER -> "🐧"
                    else -> "🖥️"
                }
                val prefKey = when (mode) {
                    SshCollector.MODE_WIN_SERVER -> SshCollector.KEY_WIN_SERVER_URL
                    SshCollector.MODE_LINUX_SERVER -> SshCollector.KEY_LINUX_SERVER_URL
                    SshCollector.MODE_MAC_SERVER -> SshCollector.KEY_MAC_SERVER_URL
                    else -> SshCollector.KEY_SERVER_URL
                }
                val defaultUrl = when (mode) {
                    SshCollector.MODE_WIN_SERVER -> SshCollector.DEFAULT_WIN_SERVER_URL
                    SshCollector.MODE_LINUX_SERVER -> SshCollector.DEFAULT_LINUX_SERVER_URL
                    SshCollector.MODE_MAC_SERVER -> SshCollector.DEFAULT_MAC_SERVER_URL
                    else -> SshCollector.DEFAULT_SERVER_URL
                }

                val serverUrl = prefs.getString(prefKey, defaultUrl)?.trim() ?: defaultUrl

                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val base = serverUrl.trimEnd('/')
                        val endpoint = if (base.endsWith("/api/telemetry")) base else "$base/api/telemetry"
                        val url = URL(endpoint)
                        val conn = (url.openConnection() as HttpURLConnection).apply {
                            connectTimeout = 4000
                            readTimeout = 4000
                        }

                        if (conn.responseCode == 200) {
                            val json = conn.inputStream.bufferedReader().readText()
                            val sample = Gson().fromJson(json, TelemetrySample::class.java)

                            if (sample != null) {
                                TelemetryRepository.addSample(this@MainActivity, sample, mode)
                                withContext(Dispatchers.Main) {
                                    binding.progressBar.visibility = View.GONE
                                    binding.swipeRefresh.isRefreshing = false
                                    injectTelemetryIntoWebView()
                                    NotificationHelper.checkThresholdAndAlert(this@MainActivity, sample)

                                    binding.btnErrorLog.visibility = View.GONE
                                    binding.tvStatus.text = "$serverIcon $serverName"
                                    binding.tvStatus.setTextColor(Color.parseColor("#34D399"))
                                }

                                // Check ping target parity with server
                                if (!hasPromptedPingMismatch) {
                                    try {
                                        val configUrl = URL("$base/api/config")
                                        val configConn = (configUrl.openConnection() as HttpURLConnection).apply {
                                            connectTimeout = 2000
                                            readTimeout = 2000
                                        }
                                        if (configConn.responseCode == 200) {
                                            val configJson = configConn.inputStream.bufferedReader().readText()
                                            val serverObj = org.json.JSONObject(configJson)
                                            val serverPing = serverObj.optString("ping_target", "")
                                            val appPing = prefs.getString(SshCollector.KEY_PING_TARGET, SshCollector.DEFAULT_PING_TARGET) ?: SshCollector.DEFAULT_PING_TARGET

                                            if (serverPing.isNotEmpty() && serverPing != appPing) {
                                                withContext(Dispatchers.Main) {
                                                    hasPromptedPingMismatch = true
                                                    showPingTargetMismatchDialog(base, serverName, serverPing, appPing)
                                                }
                                            }
                                        }
                                    } catch (_: Exception) {}
                                }
                            }
                        } else {
                            throw Exception("HTTP ${conn.responseCode}: ${conn.responseMessage}")
                        }
                    } catch (e: Exception) {
                        ErrorLogManager.logError("$serverName Probe ($serverUrl)", e)
                        withContext(Dispatchers.Main) {
                            binding.progressBar.visibility = View.GONE
                            binding.swipeRefresh.isRefreshing = false
                            binding.btnErrorLog.visibility = View.VISIBLE
                            binding.tvStatus.text = "Error ($serverName)"
                            binding.tvStatus.setTextColor(Color.parseColor("#EF4444"))

                            if (!isSilent) {
                                showErrorDetailsDialog()
                            }
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
     * Displays the "About HotBoi" dialog matching Lagatron & Keyboardy's exact design system,
     * featuring hero identity, live diagnostic telemetry, architecture pillars, release notes, and GitHub links.
     */
    private fun showAboutHotBoiDialog() {
        val density = resources.displayMetrics.density
        val strokePx = (1f * density).toInt().coerceAtLeast(1)

        val cardBg = ContextCompat.getColor(this, R.color.card_bg)
        val cardBorder = ContextCompat.getColor(this, R.color.card_border)
        val textPrimary = ContextCompat.getColor(this, R.color.text_primary)
        val textSecondary = ContextCompat.getColor(this, R.color.text_secondary)
        val textMuted = ContextCompat.getColor(this, R.color.text_muted)
        val brandRed = ContextCompat.getColor(this, R.color.hotboi_brand)
        val cyan = ContextCompat.getColor(this, R.color.primary_cyan)
        val emerald = ContextCompat.getColor(this, R.color.accent_emerald)
        val bgDark = ContextCompat.getColor(this, R.color.bg_dark)

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(20), dpToPx(18), dpToPx(20), dpToPx(16))
            background = createRoundedDrawable(
                fillColor = cardBg,
                cornerRadiusPx = 20f * density,
                strokeWidthPx = strokePx,
                strokeColor = cardBorder
            )
        }

        // 1. HERO IDENTITY CARD
        val heroCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(14), dpToPx(12), dpToPx(14), dpToPx(12))
            background = createRoundedDrawable(
                fillColor = bgDark,
                cornerRadiusPx = 14f * density,
                strokeWidthPx = strokePx,
                strokeColor = cardBorder
            )
        }

        val heroTopRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val appIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            background = createRoundedDrawable(
                fillColor = cardBg,
                cornerRadiusPx = 10f * density,
                strokeWidthPx = strokePx,
                strokeColor = cardBorder
            )
            setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
            layoutParams = LinearLayout.LayoutParams(dpToPx(44), dpToPx(44)).apply {
                marginEnd = dpToPx(12)
            }
        }
        heroTopRow.addView(appIcon)

        val heroTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvAppName = TextView(this).apply {
            text = "HotBoi"
            setTextColor(brandRed)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.02f
        }
        heroTextCol.addView(tvAppName)

        val tvAppSubtitle = TextView(this).apply {
            text = "Universal SFP+ Optical Telemetry"
            setTextColor(textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        heroTextCol.addView(tvAppSubtitle)
        heroTopRow.addView(heroTextCol)

        // Version Chip
        val vName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0-RC1"
        } catch (_: Exception) {
            "1.0.0-RC1"
        }

        val versionCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        val versionPill = TextView(this).apply {
            text = "v$vName"
            setTextColor(cyan)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setPadding(dpToPx(7), dpToPx(3), dpToPx(7), dpToPx(3))
            background = createRoundedDrawable(
                fillColor = cardBg,
                cornerRadiusPx = 6f * density,
                strokeWidthPx = strokePx,
                strokeColor = cardBorder
            )
        }
        versionCol.addView(versionPill)

        val buildLabel = TextView(this).apply {
            text = "Build 2"
            setTextColor(textMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            typeface = Typeface.MONOSPACE
            setPadding(0, dpToPx(2), dpToPx(2), 0)
        }
        versionCol.addView(buildLabel)
        heroTopRow.addView(versionCol)
        heroCard.addView(heroTopRow)

        // Divider inside Hero
        val heroDivider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, strokePx).apply {
                setMargins(0, dpToPx(10), 0, dpToPx(10))
            }
            setBackgroundColor(cardBorder)
        }
        heroCard.addView(heroDivider)

        // Quick Diagnostic Status Bar
        val diagRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val activeMode = prefs.getString(SshCollector.KEY_COLLECTION_MODE, SshCollector.MODE_SSH) ?: SshCollector.MODE_SSH
        val host = prefs.getString(SshCollector.KEY_GATEWAY_HOST, SshCollector.DEFAULT_HOST) ?: SshCollector.DEFAULT_HOST
        val port = prefs.getInt(SshCollector.KEY_GATEWAY_PORT, SshCollector.DEFAULT_PORT)
        val sfpIface = prefs.getString(SshCollector.KEY_SFP_INTERFACE, SshCollector.DEFAULT_SFP_INTERFACE) ?: SshCollector.DEFAULT_SFP_INTERFACE
        val threshold = prefs.getFloat(SshCollector.KEY_ALERT_THRESHOLD, SshCollector.DEFAULT_ALERT_THRESHOLD.toFloat())

        val latest = TelemetryRepository.getLatestSample(this)
        val isEngineConnected = latest != null

        // Status indicator dot + text
        val statusContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val statusDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(7), dpToPx(7)).apply {
                marginEnd = dpToPx(6)
            }
            background = createRoundedDrawable(
                fillColor = if (isEngineConnected) emerald else ContextCompat.getColor(this@MainActivity, R.color.hotboi_brand),
                cornerRadiusPx = 4f * density
            )
        }
        val statusText = TextView(this).apply {
            text = when (activeMode) {
                SshCollector.MODE_SSH -> "Direct SSH ($host:$port)"
                SshCollector.MODE_MAC_SERVER -> "macOS Server"
                SshCollector.MODE_WIN_SERVER -> "Windows Server"
                SshCollector.MODE_LINUX_SERVER -> "Linux Server"
                else -> "Offline Mode"
            }
            setTextColor(if (isEngineConnected) emerald else brandRed)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.DEFAULT_BOLD
        }
        statusContainer.addView(statusDot)
        statusContainer.addView(statusText)
        diagRow.addView(statusContainer)

        val portText = TextView(this).apply {
            text = "Port: $sfpIface"
            setTextColor(textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dpToPx(6), 0, dpToPx(6), 0)
        }
        diagRow.addView(portText)

        val alertText = TextView(this).apply {
            text = "Trip: ${threshold.toInt()}°C"
            setTextColor(textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.DEFAULT_BOLD
        }
        diagRow.addView(alertText)

        heroCard.addView(diagRow)
        container.addView(heroCard)

        // Scroll Area for details
        val scrollArea = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(10)
            }
        }

        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // SECTION 1: ARCHITECTURE & ZERO-DEPENDENCY ENGINE
        val secArchTitle = TextView(this).apply {
            text = "ZERO-OVERHEAD STANDALONE ARCHITECTURE"
            setTextColor(cyan)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.05f
            setPadding(0, dpToPx(6), 0, dpToPx(4))
        }
        scrollContent.addView(secArchTitle)

        val secArchDesc = TextView(this).apply {
            text = "HotBoi is a standalone on-device telemetry monitor engineered for your UniFi Cloud Gateway Fiber (White Bean) and XGS-PON SFP+ optical stick transceivers.\n\nCommunicates directly from your Android phone to your gateway over Wi-Fi via embedded BouncyCastle Ed25519 SSH, with zero reliance on cloud relays, middleman servers, or external computers."
            setTextColor(textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setLineSpacing(dpToPx(3).toFloat(), 1f)
        }
        scrollContent.addView(secArchDesc)

        // SECTION 2: TELEMETRY PILLARS
        val secFeatTitle = TextView(this).apply {
            text = "TELEMETRY & HARDWARE CAPABILITIES"
            setTextColor(cyan)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.05f
            setPadding(0, dpToPx(12), 0, dpToPx(4))
        }
        scrollContent.addView(secFeatTitle)

        val feats = listOf(
            "Real-time Laser Optics & Dual CPU thermals (Core 0 / Core 1)",
            "Optical signal diagnostics (Average Rx / Tx power in dBm)",
            "Laser Bias Current telemetry (mA) & PLOAM state tracking",
            "Continuous ICMP gateway round-trip ping latency",
            "Automated background WorkManager polling & thermal trip push notifications",
            "Instant one-tap Dual °C / °F conversion & Light / Dark mode engine"
        )
        for (f in feats) {
            val tvFeat = TextView(this).apply {
                text = "• $f"
                setTextColor(textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setLineSpacing(dpToPx(2).toFloat(), 1f)
                setPadding(0, dpToPx(2), 0, dpToPx(2))
            }
            scrollContent.addView(tvFeat)
        }

        // SECTION 3: RELEASE CANDIDATE 1 & WHAT'S NEW BOX
        val secRcTitle = TextView(this).apply {
            text = "RELEASE CANDIDATE 1 & WHAT'S NEW"
            setTextColor(cyan)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.05f
            setPadding(0, dpToPx(12), 0, dpToPx(4))
        }
        scrollContent.addView(secRcTitle)

        val whatsNewBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
            background = createRoundedDrawable(
                fillColor = bgDark,
                cornerRadiusPx = 10f * density,
                strokeWidthPx = strokePx,
                strokeColor = cardBorder
            )
        }

        val whatsNewText = TextView(this).apply {
            text = "• Release Candidate 1 (RC1) milestone build\n" +
                    "• Top bar Info button & dedicated About HotBoi diagnostic screen\n" +
                    "• 1-Tap quick Light / Dark theme toggle directly in top app bar\n" +
                    "• Modern vector drawables & responsive edge-to-edge UI\n" +
                    "• Physical tactile haptic feedback on refreshes and unit toggles\n" +
                    "• Screen-off radio power awareness to eliminate background Wi-Fi drain\n" +
                    "• R8 minification & resource shrinking for faster launch & reduced footprint"
            setTextColor(textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setLineSpacing(dpToPx(3).toFloat(), 1f)
        }
        whatsNewBox.addView(whatsNewText)
        scrollContent.addView(whatsNewBox)

        // SECTION 4: LINKS & RESOURCES
        val secLinksTitle = TextView(this).apply {
            text = "LINKS & RESOURCES"
            setTextColor(cyan)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.05f
            setPadding(0, dpToPx(12), 0, dpToPx(6))
        }
        scrollContent.addView(secLinksTitle)

        val linksRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        fun createLinkButton(title: String, url: String): TextView {
            return TextView(this).apply {
                text = title
                setTextColor(cyan)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dpToPx(8), dpToPx(7), dpToPx(8), dpToPx(7))
                background = createRoundedDrawable(
                    fillColor = bgDark,
                    cornerRadiusPx = 8f * density,
                    strokeWidthPx = strokePx,
                    strokeColor = cardBorder
                )
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dpToPx(3), 0, dpToPx(3), 0)
                }
                setOnClickListener {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (_: Exception) {
                        Toast.makeText(this@MainActivity, "Could not open browser", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        linksRow.addView(createLinkButton("Changelog ↗", "https://github.com/medusko/HotBoi/blob/main/CHANGELOG.md"))
        linksRow.addView(createLinkButton("Releases ↗", "https://github.com/medusko/HotBoi/releases"))
        linksRow.addView(createLinkButton("Source Code ↗", "https://github.com/medusko/HotBoi"))
        scrollContent.addView(linksRow)

        // FOOTER ATTRIBUTION
        val footerText = TextView(this).apply {
            text = "HotBoi v$vName (Build 2) • Release Candidate 1 • Built for Android 15 / Pixel\nDesigned & Engineered by Medusko"
            setTextColor(textMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            gravity = Gravity.CENTER
            setPadding(0, dpToPx(12), 0, dpToPx(4))
        }
        scrollContent.addView(footerText)

        scrollArea.addView(scrollContent)
        container.addView(scrollArea)

        // Close Button
        val btnClose = Button(this).apply {
            text = "Close"
            setTextColor(cyan)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            background = createRoundedDrawable(
                fillColor = bgDark,
                cornerRadiusPx = 10f * density,
                strokeWidthPx = strokePx,
                strokeColor = cardBorder
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpToPx(42)
            ).apply {
                topMargin = dpToPx(10)
            }
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                dialog.dismiss()
            }
        }
        container.addView(btnClose)

        dialog.setContentView(container)

        // Set dialog window width
        val displayWidth = resources.displayMetrics.widthPixels
        dialog.window?.setLayout((displayWidth * 0.92f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)

        dialog.show()
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
            dialog.dismiss()
            Toast.makeText(this, "Error history cleared", Toast.LENGTH_SHORT).show()
        }

        btnCopy.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("HotBoi Error Diagnostic", "System: $modeText\nSummary: ${tvSummary.text}\nDetails:\n${tvDetails.text}")
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
        val etServerUrl = dialogView.findViewById<EditText>(R.id.etServerUrl)

        val etHost = dialogView.findViewById<EditText>(R.id.etHost)
        val etPort = dialogView.findViewById<EditText>(R.id.etPort)
        val etUser = dialogView.findViewById<EditText>(R.id.etUser)
        val etPassword = dialogView.findViewById<EditText>(R.id.etPassword)
        val etSshKey = dialogView.findViewById<EditText>(R.id.etSshKey)
        val btnLoadMacKey = dialogView.findViewById<TextView>(R.id.btnLoadMacKey)
        val btnTest = dialogView.findViewById<Button>(R.id.btnTestConnection)

        val spPing = dialogView.findViewById<Spinner>(R.id.spPingTarget)
        val etCustomPing = dialogView.findViewById<EditText>(R.id.etCustomPingTarget)

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

        // 1. Hardware & Interface
        etDeviceName.setText(prefs.getString(SshCollector.KEY_DEVICE_NAME, SshCollector.DEFAULT_DEVICE_NAME))

        val sfpOptions = arrayOf(
            "Port 7 SFP+ (eth6) — Cloud Gateway Fiber",
            "Port 8 SFP+ (eth7) — Cloud Gateway Fiber",
            "Port 10 SFP+ (eth9) — UDM Pro / SE / Pro Max",
            "Port 11 SFP+ (eth10) — UDM Pro / SE / Pro Max",
            "Custom Interface Name..."
        )
        val sfpAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, sfpOptions)
        spSfp.adapter = sfpAdapter

        val savedIface = prefs.getString(SshCollector.KEY_SFP_INTERFACE, SshCollector.DEFAULT_SFP_INTERFACE) ?: SshCollector.DEFAULT_SFP_INTERFACE
        when (savedIface) {
            "eth6" -> spSfp.setSelection(0)
            "eth7" -> spSfp.setSelection(1)
            "eth9" -> spSfp.setSelection(2)
            "eth10" -> spSfp.setSelection(3)
            else -> {
                spSfp.setSelection(4)
                etCustomSfp.visibility = View.VISIBLE
                etCustomSfp.setText(savedIface)
            }
        }

        spSfp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                etCustomSfp.visibility = if (position == 4) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 2. Data Collection Mode Switching
        val currentMode = prefs.getString(SshCollector.KEY_COLLECTION_MODE, SshCollector.MODE_SSH)

        fun configureServerSection(title: String, note: String, url: String) {
            sectionSsh.visibility = View.GONE
            sectionServer.visibility = View.VISIBLE
            tvServerTitle.text = title
            tvServerBetaNote.text = note
            etServerUrl.setText(url)
        }

        when (currentMode) {
            SshCollector.MODE_MAC_SERVER -> {
                rbMacServer.isChecked = true
                configureServerSection(
                    "🍏 MACOS BACKGROUND SERVER (BETA)",
                    "HotBoi will fetch continuous telemetry from your Mac daemon at 192.168.0.134:8080.",
                    prefs.getString(SshCollector.KEY_MAC_SERVER_URL, SshCollector.DEFAULT_MAC_SERVER_URL) ?: SshCollector.DEFAULT_MAC_SERVER_URL
                )
            }
            SshCollector.MODE_WIN_SERVER -> {
                rbWinServer.isChecked = true
                configureServerSection(
                    "🪟 WINDOWS BACKGROUND SERVER (BETA)",
                    "HotBoi will fetch continuous telemetry from your Windows service at 192.168.0.171:8080.",
                    prefs.getString(SshCollector.KEY_WIN_SERVER_URL, SshCollector.DEFAULT_WIN_SERVER_URL) ?: SshCollector.DEFAULT_WIN_SERVER_URL
                )
            }
            SshCollector.MODE_LINUX_SERVER -> {
                rbLinuxServer.isChecked = true
                configureServerSection(
                    "🐧 LINUX BACKGROUND SERVER (BETA)",
                    "HotBoi will fetch continuous telemetry from your Debian / Ubuntu server daemon at 192.168.0.150:8080.",
                    prefs.getString(SshCollector.KEY_LINUX_SERVER_URL, SshCollector.DEFAULT_LINUX_SERVER_URL) ?: SshCollector.DEFAULT_LINUX_SERVER_URL
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
                R.id.rbModeDirectSsh -> {
                    sectionSsh.visibility = View.VISIBLE
                    sectionServer.visibility = View.GONE
                }
                R.id.rbModeMacServer -> {
                    configureServerSection(
                        "🍏 MACOS BACKGROUND SERVER (BETA)",
                        "HotBoi will fetch continuous telemetry from your Mac daemon at 192.168.0.134:8080.",
                        prefs.getString(SshCollector.KEY_MAC_SERVER_URL, SshCollector.DEFAULT_MAC_SERVER_URL) ?: SshCollector.DEFAULT_MAC_SERVER_URL
                    )
                }
                R.id.rbModeWinServer -> {
                    configureServerSection(
                        "🪟 WINDOWS BACKGROUND SERVER (BETA)",
                        "HotBoi will fetch continuous telemetry from your Windows service at 192.168.0.171:8080.",
                        prefs.getString(SshCollector.KEY_WIN_SERVER_URL, SshCollector.DEFAULT_WIN_SERVER_URL) ?: SshCollector.DEFAULT_WIN_SERVER_URL
                    )
                }
                R.id.rbModeLinuxServer -> {
                    configureServerSection(
                        "🐧 LINUX BACKGROUND SERVER (BETA)",
                        "HotBoi will fetch continuous telemetry from your Debian / Ubuntu server daemon at 192.168.0.150:8080.",
                        prefs.getString(SshCollector.KEY_LINUX_SERVER_URL, SshCollector.DEFAULT_LINUX_SERVER_URL) ?: SshCollector.DEFAULT_LINUX_SERVER_URL
                    )
                }
                R.id.rbModeOffline -> {
                    sectionSsh.visibility = View.GONE
                    sectionServer.visibility = View.GONE
                }
            }
        }

        // 3. Ping Target Selector
        val pingOptions = arrayOf(
            "1.1.1.1 (Cloudflare)",
            "8.8.8.8 (Google)",
            "9.9.9.9 (Quad9)",
            "Custom Target..."
        )
        val pingAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, pingOptions)
        spPing.adapter = pingAdapter

        val savedPing = prefs.getString(SshCollector.KEY_PING_TARGET, SshCollector.DEFAULT_PING_TARGET) ?: SshCollector.DEFAULT_PING_TARGET
        when (savedPing) {
            "1.1.1.1" -> spPing.setSelection(0)
            "8.8.8.8" -> spPing.setSelection(1)
            "9.9.9.9" -> spPing.setSelection(2)
            else -> {
                spPing.setSelection(3)
                etCustomPing.visibility = View.VISIBLE
                etCustomPing.setText(savedPing)
            }
        }

        spPing.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                etCustomPing.visibility = if (position == 3) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 4. Background Scheduling
        val intervalOptions = arrayOf("Every 15 minutes", "Every 30 minutes", "Every 1 hour (Recommended)", "Every 2 hours")
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
                    updateThemeIcon()
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
        updateThemeIcon()
        refreshHandler.postDelayed(autoRefreshRunnable, AUTO_REFRESH_INTERVAL_MS)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(autoRefreshRunnable)
    }
}
