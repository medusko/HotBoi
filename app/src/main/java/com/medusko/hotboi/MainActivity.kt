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
import android.content.res.ColorStateList
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
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
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
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.TextViewCompat
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
 * 2. Instant Theme Engine: Zero-flash, in-place switching between Light Mode and Dark Mode.
 * 3. Multi-Mode Telemetry:
 *    - Direct SSH (BouncyCastle Ed25519 authenticated direct from phone to gateway/switch).
 *    - macOS Server Daemon (BETA).
 *    - Windows 10/11 Server Background Service (BETA).
 *    - Linux Server Daemon for Debian / Ubuntu (BETA).
 *    - Offline Cached Telemetry (local history).
 * 4. Dynamic SFP+ Port Discovery & Per-Port History Isolation.
 * 5. Dual Temperature Units: Instant one-tap conversion between Celsius (°C) and Fahrenheit (°F).
 * 6. High-Priority Push Alerts: Medusko-branded notification upon crossing thermal trip limit.
 * 7. About HotBoi Diagnostic & Information Screen matching Medusko design systems.
 * 8. Full-Screen Settings & Setup experience.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private val refreshHandler = Handler(Looper.getMainLooper())
    private var isPageLoaded = false
    private var hasPromptedPingMismatch = false
    private var hasPerformedInitialRefresh = false
    private var isShowingFirstLaunchDialog = false

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

        // Migrate legacy hardcoded default device name to generic label
        if (prefs.getString(SshCollector.KEY_DEVICE_NAME, null) == "UniFi Cloud Gateway Fiber (White Bean)") {
            prefs.edit().putString(SshCollector.KEY_DEVICE_NAME, SshCollector.DEFAULT_DEVICE_NAME).apply()
        }

        checkAndRequestNotificationPermission()
        setupWindowInsets()
        setupBackNavigation()
        setupWebView()
        setupListeners()
        applyNativeThemeColors()
        updateStatusBadgeForMode()
        setupAds()

        // Load local asset dashboard
        binding.webView.loadUrl(LOCAL_URL)

        // Trigger welcome initial setup screen on first run
        if (!prefs.getBoolean(KEY_FIRST_LAUNCH, false)) {
            isShowingFirstLaunchDialog = true
            prefs.edit().putBoolean(KEY_FIRST_LAUNCH, true).apply()
            showSettingsDialog(isFirstLaunch = true)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyNativeThemeColors()
        updateWebViewDisplayPreferences()
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
                    isEnabled = true
                }
            }
        })
    }

    /**
     * Configures Android WebView with adaptive theming and zero splash flash.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = binding.webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        binding.webView.setBackgroundColor(Color.TRANSPARENT)

        binding.webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("HotBoiJS", "${consoleMessage?.message()} -- line ${consoleMessage?.lineNumber()}")
                }
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

                if (!hasPerformedInitialRefresh && !isShowingFirstLaunchDialog) {
                    hasPerformedInitialRefresh = true
                    triggerDataRefresh(isSilent = true)
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

    private data class ThemeColors(
        val bgMain: Int,
        val bgDark: Int,
        val cardBg: Int,
        val cardBorder: Int,
        val brandBlue: Int,
        val cyan: Int,
        val emerald: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val textMuted: Int
    )

    private fun getActiveThemeColors(): ThemeColors {
        val isNight = isNightModeActive()
        return if (isNight) {
            ThemeColors(
                bgMain = Color.parseColor("#101216"),
                bgDark = Color.parseColor("#222631"),
                cardBg = Color.parseColor("#191C23"),
                cardBorder = Color.parseColor("#2E3342"),
                brandBlue = Color.parseColor("#60A5FA"),
                cyan = Color.parseColor("#60A5FA"),
                emerald = Color.parseColor("#00C853"),
                textPrimary = Color.parseColor("#FFFFFF"),
                textSecondary = Color.parseColor("#B8C2D4"),
                textMuted = Color.parseColor("#8B95A5")
            )
        } else {
            ThemeColors(
                bgMain = Color.parseColor("#F6F1E8"),
                bgDark = Color.parseColor("#ECE5D9"),
                cardBg = Color.parseColor("#FFFDF9"),
                cardBorder = Color.parseColor("#DCD4C6"),
                brandBlue = Color.parseColor("#2E6BD9"),
                cyan = Color.parseColor("#2E6BD9"),
                emerald = Color.parseColor("#00C853"),
                textPrimary = Color.parseColor("#1E1A16"),
                textSecondary = Color.parseColor("#6B6358"),
                textMuted = Color.parseColor("#948B7D")
            )
        }
    }

    /**
     * Applies native colors and icons in-place without activity recreation or screen flash.
     */
    private fun applyNativeThemeColors() {
        val isNight = isNightModeActive()
        val tc = getActiveThemeColors()
        val density = resources.displayMetrics.density
        val strokePx = (1f * density).toInt().coerceAtLeast(1)

        @Suppress("DEPRECATION")
        window.statusBarColor = tc.bgMain
        @Suppress("DEPRECATION")
        window.navigationBarColor = tc.bgMain
        window.setBackgroundDrawable(ColorDrawable(tc.bgMain))

        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isNight
            isAppearanceLightNavigationBars = !isNight
        }

        binding.rootLayout.setBackgroundColor(tc.bgMain)
        binding.topBar.setBackgroundColor(tc.bgMain)
        binding.divider.setBackgroundColor(tc.cardBorder)
        binding.topBarDivider.setBackgroundColor(tc.cardBorder)
        binding.webView.setBackgroundColor(tc.bgMain)

        binding.tvAppTitle.setTextColor(tc.brandBlue)
        binding.btnUnitToggle.setTextColor(tc.cyan)

        val pills = listOf(
            binding.tvStatus,
            binding.btnUnitToggle,
            binding.btnErrorLog,
            binding.btnAbout,
            binding.btnThemeToggle,
            binding.btnRefresh,
            binding.btnSettings
        )
        for (pill in pills) {
            pill.background = createRoundedDrawable(
                fillColor = tc.cardBg,
                cornerRadiusPx = 12f * density,
                strokeWidthPx = strokePx,
                strokeColor = tc.cardBorder
            )
        }

        binding.ivAboutIcon.setColorFilter(tc.textPrimary)
        binding.ivThemeIcon.setColorFilter(tc.textPrimary)
        binding.ivRefreshIcon.setColorFilter(tc.textPrimary)
        binding.ivSettingsIcon.setColorFilter(tc.textPrimary)

        updateThemeIcon()
    }

    /**
     * Updates top-bar theme toggle button icon to reflect current UI state:
     * Light mode shows Sun icon, Dark mode shows Moon icon.
     */
    private fun updateThemeIcon() {
        val isNight = isNightModeActive()
        binding.ivThemeIcon.setImageResource(
            if (isNight) R.drawable.ic_dark_mode else R.drawable.ic_light_mode
        )
    }

    /**
     * 1-Tap quick toggle between Dark Mode and Light Mode with zero activity flash.
     */
    private fun toggleTheme() {
        val isNight = isNightModeActive()
        val newTheme = if (isNight) SshCollector.THEME_LIGHT else SshCollector.THEME_DARK
        prefs.edit().putString(SshCollector.KEY_THEME_MODE, newTheme).apply()

        when (newTheme) {
            SshCollector.THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            SshCollector.THEME_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        }
        applyNativeThemeColors()
        updateWebViewDisplayPreferences()
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
     * Helper to set a tinted Medusko vector icon on a TextView pill without emojis.
     */
    private fun setStatusBadgeContent(label: String, iconRes: Int, colorHex: String) {
        val colorInt = Color.parseColor(colorHex)
        binding.tvStatus.text = label
        binding.tvStatus.setTextColor(colorInt)
        val drawable = ContextCompat.getDrawable(this, iconRes)?.mutate()
        if (drawable != null) {
            val sizePx = dpToPx(13)
            drawable.setBounds(0, 0, sizePx, sizePx)
            DrawableCompat.setTint(drawable, colorInt)
            binding.tvStatus.setCompoundDrawablesRelative(drawable, null, null, null)
        } else {
            binding.tvStatus.setCompoundDrawablesRelative(null, null, null, null)
        }
    }

    /**
     * Updates top-bar status pill to reflect the active collection mode using Medusko vector icons.
     */
    private fun updateStatusBadgeForMode() {
        val mode = prefs.getString(SshCollector.KEY_COLLECTION_MODE, SshCollector.MODE_SSH) ?: SshCollector.MODE_SSH
        when (mode) {
            SshCollector.MODE_SSH -> setStatusBadgeContent("Direct SSH", R.drawable.ic_mode_ssh, "#34D399")
            SshCollector.MODE_MAC_SERVER -> setStatusBadgeContent("macOS Server", R.drawable.ic_os_macos, "#34D399")
            SshCollector.MODE_WIN_SERVER -> setStatusBadgeContent("Windows Server", R.drawable.ic_os_windows, "#34D399")
            SshCollector.MODE_LINUX_SERVER -> setStatusBadgeContent("Linux Server", R.drawable.ic_os_linux, "#34D399")
            SshCollector.MODE_SERVER -> setStatusBadgeContent("Server", R.drawable.ic_mode_server, "#34D399")
            else -> setStatusBadgeContent("Offline (Cached)", R.drawable.ic_mode_offline, "#94A3B8")
        }
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
            updateWebViewDisplayPreferences()
            val label = if (newUnit == SshCollector.UNIT_FAHRENHEIT) "Switched to Fahrenheit (°F)" else "Switched to Celsius (°C)"
            Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
        }

        // About HotBoi Dialog trigger
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
     * Fast in-place WebView display update for unit (°C/°F) and theme (Light/Dark) toggles,
     * avoiding full 2,500-sample JSON re-serialization on every tap.
     */
    private fun updateWebViewDisplayPreferences() {
        if (!isPageLoaded) {
            injectTelemetryIntoWebView()
            return
        }
        val unit = prefs.getString(SshCollector.KEY_TEMP_UNIT, SshCollector.UNIT_CELSIUS) ?: SshCollector.UNIT_CELSIUS
        val isLight = !isNightModeActive()
        binding.webView.evaluateJavascript("window.applyDisplayPreferences('$unit', $isLight);") { result ->
            if (result != "true") {
                injectTelemetryIntoWebView()
            }
        }
    }

    /**
     * Serializes telemetry bundle from local cache on background dispatcher and invokes JS render hook in WebView.
     */
    private fun injectTelemetryIntoWebView() {
        val mode = prefs.getString(SshCollector.KEY_COLLECTION_MODE, SshCollector.MODE_SSH) ?: SshCollector.MODE_SSH
        lifecycleScope.launch(Dispatchers.IO) {
            val bundleJson = TelemetryRepository.toJsonBundle(this@MainActivity, mode)
            withContext(Dispatchers.Main) {
                if (isPageLoaded && !isFinishing && !isDestroyed) {
                    binding.webView.evaluateJavascript("window.renderDashboardWithData($bundleJson);", null)
                }
            }
        }
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
                    val sfpIface = prefs.getString(SshCollector.KEY_SFP_INTERFACE, SshCollector.DEFAULT_SFP_INTERFACE) ?: SshCollector.DEFAULT_SFP_INTERFACE
                    val result = SshCollector.collectViaSsh(this@MainActivity)
                    if (result.isSuccess) {
                        val sample = result.getOrThrow()
                        TelemetryRepository.addSample(this@MainActivity, sample, SshCollector.MODE_SSH)
                    }

                    withContext(Dispatchers.Main) {
                        binding.progressBar.visibility = View.GONE
                        binding.swipeRefresh.isRefreshing = false

                        if (result.isSuccess) {
                            val sample = result.getOrThrow()
                            injectTelemetryIntoWebView()
                            NotificationHelper.checkThresholdAndAlert(this@MainActivity, sample)

                            binding.btnErrorLog.visibility = View.GONE
                            setStatusBadgeContent("Direct SSH ($sfpIface)", R.drawable.ic_mode_ssh, "#34D399")
                        } else {
                            val err = result.exceptionOrNull()
                            val msg = err?.message ?: "Unknown SSH Error"
                            ErrorLogManager.logError("SSH Direct Probe ($host • $sfpIface)", err ?: Exception(msg))

                            // Refresh WebView so port changes with no transceiver reflect cleanly
                            injectTelemetryIntoWebView()

                            binding.btnErrorLog.visibility = View.VISIBLE
                            val badgeLabel = if (msg.contains("No SFP+ transceiver", ignoreCase = true)) {
                                "No Module ($sfpIface)"
                            } else {
                                "Error (SSH)"
                            }
                            setStatusBadgeContent(badgeLabel, R.drawable.ic_warning, "#EF4444")

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
                    var conn: HttpURLConnection? = null
                    try {
                        val base = serverUrl.trimEnd('/')
                        val endpoint = if (base.endsWith("/api/telemetry")) base else "$base/api/telemetry"
                        val url = URL(endpoint)
                        conn = (url.openConnection() as HttpURLConnection).apply {
                            connectTimeout = 4000
                            readTimeout = 4000
                        }

                        if (conn.responseCode == 200) {
                            val json = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                            val sample = Gson().fromJson(json, TelemetrySample::class.java)

                            if (sample != null) {
                                TelemetryRepository.addSample(this@MainActivity, sample, mode)
                                withContext(Dispatchers.Main) {
                                    binding.progressBar.visibility = View.GONE
                                    binding.swipeRefresh.isRefreshing = false
                                    injectTelemetryIntoWebView()
                                    NotificationHelper.checkThresholdAndAlert(this@MainActivity, sample)

                                    binding.btnErrorLog.visibility = View.GONE
                                    setStatusBadgeContent(serverName, R.drawable.ic_mode_server, "#34D399")
                                }

                                // Check ping target parity with server
                                if (!hasPromptedPingMismatch) {
                                    var configConn: HttpURLConnection? = null
                                    try {
                                        val configUrl = URL("$base/api/config")
                                        configConn = (configUrl.openConnection() as HttpURLConnection).apply {
                                            connectTimeout = 2000
                                            readTimeout = 2000
                                        }
                                        if (configConn.responseCode == 200) {
                                            val configJson = configConn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
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
                                    } catch (_: Exception) {
                                    } finally {
                                        configConn?.disconnect()
                                    }
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
                            setStatusBadgeContent("Error ($serverName)", R.drawable.ic_warning, "#EF4444")

                            if (!isSilent) {
                                showErrorDetailsDialog()
                            }
                        }
                    } finally {
                        conn?.disconnect()
                    }
                }
            }

            else -> {
                // Offline mode: zero network calls, read entirely from local cache
                injectTelemetryIntoWebView()
                binding.btnErrorLog.visibility = View.GONE
                setStatusBadgeContent("Offline (Cached)", R.drawable.ic_mode_offline, "#94A3B8")
                binding.swipeRefresh.isRefreshing = false
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    /**
     * Prompts the user when a ping target discrepancy is detected between the host server and mobile app.
     */
    private fun showPingTargetMismatchDialog(serverBase: String, serverName: String, serverPing: String, appPing: String) {
        if (isFinishing || isDestroyed) return

        AlertDialog.Builder(this)
            .setTitle("Ping Target Discrepancy")
            .setMessage(
                "Host Collector ($serverName) is currently pinging:\n• $serverPing\n\n" +
                "HotBoi on this device is configured for:\n• $appPing\n\n" +
                "Would you like to push the app's ping configuration to the host server, or inherit the server's ping target?"
            )
            .setPositiveButton("Push to Server ($appPing)") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    var conn: HttpURLConnection? = null
                    try {
                        val url = URL("$serverBase/api/config")
                        conn = (url.openConnection() as HttpURLConnection).apply {
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
                                Toast.makeText(this@MainActivity, "Successfully pushed $appPing to $serverName", Toast.LENGTH_LONG).show()
                                triggerDataRefresh(isSilent = true)
                            } else {
                                Toast.makeText(this@MainActivity, "Server returned HTTP $code", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Push failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                        }
                    } finally {
                        conn?.disconnect()
                    }
                }
            }
            .setNegativeButton("Inherit from Server ($serverPing)") { _, _ ->
                prefs.edit()
                    .putString(SshCollector.KEY_PING_TARGET, serverPing)
                    .putString(SshCollector.KEY_PING_TARGET_LABEL, serverPing)
                    .apply()
                injectTelemetryIntoWebView()
                Toast.makeText(this@MainActivity, "Inherited $serverPing from $serverName", Toast.LENGTH_LONG).show()
            }
            .setNeutralButton("Keep Separate", null)
            .show()
    }

    /**
     * Displays the full-screen "About HotBoi" page matching Keyboardy RC2's layout,
     * typography, What's New card, 2x2 Links & Resources, Contact card, Legal disclaimer,
     * and RC2 app icon header.
     */
    private fun showAboutHotBoiDialog() {
        val density = resources.displayMetrics.density
        val strokePx = (1f * density).toInt().coerceAtLeast(1)
        val tc = getActiveThemeColors()
        val vName = BuildConfig.VERSION_NAME
        val vCode = BuildConfig.VERSION_CODE

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(20), dpToPx(18), dpToPx(20), dpToPx(18))
            background = createRoundedDrawable(
                fillColor = tc.cardBg,
                cornerRadiusPx = 20f * density,
                strokeWidthPx = strokePx,
                strokeColor = tc.cardBorder
            )
        }

        // Title Row (Matches Keyboardy RC2: Info Icon + "About HotBoi" + borderless X close button)
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(12)
            }
        }

        val iconView = ImageView(this).apply {
            setImageResource(R.drawable.ic_info)
            setColorFilter(tc.cyan)
            layoutParams = LinearLayout.LayoutParams(dpToPx(22), dpToPx(22)).apply {
                marginEnd = dpToPx(8)
            }
        }
        titleRow.addView(iconView)

        val titleText = TextView(this).apply {
            text = "About HotBoi"
            setTextColor(tc.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        titleRow.addView(titleText)

        // Top-Right Close Button (X)
        val topCloseBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(tc.textSecondary)
            val pad = dpToPx(5)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(dpToPx(28), dpToPx(28))
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
            setBackgroundResource(outValue.resourceId)
            contentDescription = "Close"
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                dialog.dismiss()
            }
        }
        titleRow.addView(topCloseBtn)
        container.addView(titleRow)

        // Scrollable Content Area (Fills full screen between header and bottom Close row)
        val scrollView = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Compact RC2 App Icon Identity Row (Ensures the RC2 icon is displayed on the About page)
        val identityCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
            background = createRoundedDrawable(
                fillColor = tc.bgDark,
                cornerRadiusPx = 10f * density,
                strokeWidthPx = strokePx,
                strokeColor = tc.cardBorder
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(10)
            }
        }

        val appIcon = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            layoutParams = LinearLayout.LayoutParams(dpToPx(42), dpToPx(42)).apply {
                marginEnd = dpToPx(12)
            }
        }
        identityCard.addView(appIcon)

        val identityTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val identityTitleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        identityTitleRow.addView(TextView(this).apply {
            text = "HotBoi"
            setTextColor(tc.brandBlue)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
        })
        identityTitleRow.addView(TextView(this).apply {
            text = "RC2"
            setTextColor(Color.parseColor("#FF6D00"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dpToPx(6), dpToPx(1), dpToPx(6), dpToPx(1))
            background = createRoundedDrawable(
                fillColor = tc.cardBg,
                cornerRadiusPx = 5f * density,
                strokeWidthPx = strokePx,
                strokeColor = Color.parseColor("#FF6D00")
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dpToPx(7)
            }
        })
        identityTextCol.addView(identityTitleRow)
        identityTextCol.addView(TextView(this).apply {
            text = "Universal SFP+ / SFP28 Optical & Gateway Telemetry"
            setTextColor(tc.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        })
        identityCard.addView(identityTextCol)
        scrollContent.addView(identityCard)

        // 1. About Section
        val aboutSectionLabel = TextView(this).apply {
            text = "About"
            setTextColor(tc.cyan)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
        }
        scrollContent.addView(aboutSectionLabel)

        val bodyText = TextView(this).apply {
            text = "HotBoi is a standalone on-device thermal and optical telemetry monitor for network gateways, switches, and SFP+ / SFP28 optical transceivers.\n\n" +
                    "Designed for network engineers and homelab operators, HotBoi connects directly from your Android device over SSH (Ed25519 / RSA / password) or optional host collector servers to stream live laser optics thermals, dual-core CPU temperatures, optical Rx/Tx signal power (dBm), laser bias current (mA), and ICMP round-trip ping latency with automated threshold alerts."
            setTextColor(tc.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setLineSpacing(dpToPx(3).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(6)
            }
        }
        scrollContent.addView(bodyText)

        // 2. Release Candidate 2 & What's New Section
        val whatsNewLabel = TextView(this).apply {
            text = "Release Candidate 2 & What's New"
            setTextColor(tc.cyan)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(14)
            }
        }
        scrollContent.addView(whatsNewLabel)

        val whatsNewBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val boxPadH = dpToPx(12)
            val boxPadV = dpToPx(10)
            setPadding(boxPadH, boxPadV, boxPadH, boxPadV)
            background = createRoundedDrawable(
                fillColor = tc.bgDark,
                cornerRadiusPx = 10f * density,
                strokeWidthPx = strokePx,
                strokeColor = tc.cardBorder
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(6)
            }
        }

        val whatsNewText = TextView(this).apply {
            text = "• Release Candidate 2: Live SSH SFP+ / SFP28 port discovery & per-port history isolation\n" +
                    "• Full-screen Settings & About dialogs with edge-to-edge system bar support\n" +
                    "• Custom Medusko vector iconography across UI & thermal push notifications\n" +
                    "• Instant zero-flash Light / Dark theme switching directly in top bar\n" +
                    "• Live Direct SSH telemetry without legacy CSV seed data pollution\n" +
                    "• Hardware-agnostic gateway & optical transceiver configuration"
            setTextColor(tc.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setLineSpacing(dpToPx(3).toFloat(), 1f)
        }
        whatsNewBox.addView(whatsNewText)
        scrollContent.addView(whatsNewBox)

        // 3. Links & Resources Section (Matches Keyboardy RC2 2x2 Grid + Contact Card)
        val linksLabel = TextView(this).apply {
            text = "Links & Resources"
            setTextColor(tc.cyan)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(14)
                bottomMargin = dpToPx(6)
            }
        }
        scrollContent.addView(linksLabel)

        fun createLinkButton(label: String, url: String): TextView {
            return TextView(this).apply {
                text = label
                setTextColor(tc.cyan)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                val padH = dpToPx(8)
                val padV = dpToPx(7)
                setPadding(padH, padV, padH, padV)
                background = createRoundedDrawable(
                    fillColor = tc.bgDark,
                    cornerRadiusPx = 8f * density,
                    strokeWidthPx = strokePx,
                    strokeColor = tc.cardBorder
                )
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    marginStart = dpToPx(2)
                    marginEnd = dpToPx(2)
                }
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (_: Exception) {
                        Toast.makeText(this@MainActivity, "Could not open browser", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        val linksRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        linksRow1.addView(createLinkButton("Changelog ↗", "https://github.com/medusko/HotBoi/blob/main/CHANGELOG.md"))
        linksRow1.addView(createLinkButton("Releases ↗", "https://github.com/medusko/HotBoi/releases"))
        scrollContent.addView(linksRow1)

        val linksRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(6)
            }
        }
        linksRow2.addView(createLinkButton("Source Code ↗", "https://github.com/medusko/HotBoi"))
        linksRow2.addView(createLinkButton("Sponsor ↗", "https://github.com/sponsors/Zlatty"))
        scrollContent.addView(linksRow2)

        // Contact & Social Information Card
        val contactBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val boxPadH = dpToPx(12)
            val boxPadV = dpToPx(10)
            setPadding(boxPadH, boxPadV, boxPadH, boxPadV)
            background = createRoundedDrawable(
                fillColor = tc.bgDark,
                cornerRadiusPx = 10f * density,
                strokeWidthPx = strokePx,
                strokeColor = tc.cardBorder
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(8)
            }
        }

        fun createContactRow(label: String, value: String, url: String): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dpToPx(2)
                    bottomMargin = dpToPx(2)
                }
                addView(TextView(this@MainActivity).apply {
                    text = label
                    setTextColor(tc.textSecondary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(
                        dpToPx(72),
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                })
                addView(TextView(this@MainActivity).apply {
                    text = value
                    setTextColor(tc.cyan)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    typeface = Typeface.DEFAULT_BOLD
                    setOnClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (_: Exception) {}
                    }
                })
            }
        }

        contactBox.addView(createContactRow("Website:", "medusko.com", "https://medusko.com"))
        contactBox.addView(createContactRow("Support:", "support@medusko.com", "mailto:support@medusko.com"))
        contactBox.addView(createContactRow("Social:", "@meduskodev on Twitter", "https://twitter.com/meduskodev"))
        scrollContent.addView(contactBox)

        // 4. Trademark & Legal Disclaimer (Matches Keyboardy RC2)
        val legalText = TextView(this).apply {
            text = "Android is a trademark of Google LLC. All other trademarks, service marks, trade names, and product names referenced herein are the property of their respective owners. HotBoi is an independent product developed by Medusko and is not affiliated with, endorsed by, or sponsored by Google LLC or any other referenced hardware vendor. Use of third-party names or marks is for identification and compatibility purposes only and does not imply any association with or endorsement by the trademark holders."
            setTextColor(tc.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setLineSpacing(dpToPx(2).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(12)
            }
        }
        scrollContent.addView(legalText)

        // 5. Version Footer (Matches Keyboardy RC2)
        val versionFooter = TextView(this).apply {
            text = "HotBoi v$vName (Build $vCode) • Release Candidate 2 • Built for Android 15 / Pixel\nDesigned & Engineered by Medusko"
            setTextColor(tc.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(14)
                bottomMargin = dpToPx(4)
            }
        }
        scrollContent.addView(versionFooter)

        scrollView.addView(scrollContent)
        container.addView(scrollView)

        dialog.setContentView(container)
        dialog.show()
        val width = (resources.displayMetrics.widthPixels * 0.90f).toInt()
        val maxHeight = (resources.displayMetrics.heightPixels * 0.82f).toInt()
        dialog.window?.setLayout(width, maxHeight)
    }

    /**
     * Displays system diagnostic modal with copyable stack trace, active system reflection, and modern Android design.
     */
    private fun showErrorDetailsDialog() {
        val tc = getActiveThemeColors()
        val density = resources.displayMetrics.density
        val strokePx = (1f * density).toInt().coerceAtLeast(1)

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_error_details, null)
        val diagCardContainer = dialogView.findViewById<LinearLayout>(R.id.diagCardContainer)
        val tvActiveMode = dialogView.findViewById<TextView>(R.id.tvActiveSystemMode)
        val tvSummary = dialogView.findViewById<TextView>(R.id.tvErrorSummary)
        val tvDetails = dialogView.findViewById<TextView>(R.id.tvErrorDetails)
        val btnClear = dialogView.findViewById<Button>(R.id.btnClearError)
        val btnCopy = dialogView.findViewById<Button>(R.id.btnCopyError)
        val btnRetry = dialogView.findViewById<Button>(R.id.btnRetryNow)
        val btnCloseDiagTop = dialogView.findViewById<ImageView>(R.id.btnCloseDiagTop)
        val btnCloseDiagBottom = dialogView.findViewById<Button>(R.id.btnCloseDiagBottom)

        diagCardContainer.background = createRoundedDrawable(
            fillColor = tc.cardBg,
            cornerRadiusPx = 20f * density,
            strokeWidthPx = strokePx,
            strokeColor = tc.cardBorder
        )

        val currentMode = prefs.getString(SshCollector.KEY_COLLECTION_MODE, SshCollector.MODE_SSH)
        val (modeText, modeIcon) = when (currentMode) {
            SshCollector.MODE_MAC_SERVER, SshCollector.MODE_SERVER -> {
                val url = prefs.getString(SshCollector.KEY_MAC_SERVER_URL, SshCollector.DEFAULT_MAC_SERVER_URL)
                Pair("Active System: macOS Server ($url)", R.drawable.ic_os_macos)
            }
            SshCollector.MODE_WIN_SERVER -> {
                val url = prefs.getString(SshCollector.KEY_WIN_SERVER_URL, SshCollector.DEFAULT_WIN_SERVER_URL)
                Pair("Active System: Windows Server ($url)", R.drawable.ic_os_windows)
            }
            SshCollector.MODE_LINUX_SERVER -> {
                val url = prefs.getString(SshCollector.KEY_LINUX_SERVER_URL, SshCollector.DEFAULT_LINUX_SERVER_URL)
                Pair("Active System: Linux Server ($url)", R.drawable.ic_os_linux)
            }
            SshCollector.MODE_OFFLINE -> Pair("Active System: Offline Cache Mode", R.drawable.ic_mode_offline)
            else -> {
                val host = prefs.getString(SshCollector.KEY_GATEWAY_HOST, SshCollector.DEFAULT_HOST)
                val port = prefs.getInt(SshCollector.KEY_GATEWAY_PORT, SshCollector.DEFAULT_PORT)
                val iface = prefs.getString(SshCollector.KEY_SFP_INTERFACE, SshCollector.DEFAULT_SFP_INTERFACE)
                Pair("Active System: Direct SSH ($host:$port • $iface)", R.drawable.ic_mode_ssh)
            }
        }
        tvActiveMode.text = modeText
        val iconDrawable = ContextCompat.getDrawable(this, modeIcon)?.mutate()
        if (iconDrawable != null) {
            val sizePx = dpToPx(15)
            iconDrawable.setBounds(0, 0, sizePx, sizePx)
            DrawableCompat.setTint(iconDrawable, tc.emerald)
            tvActiveMode.setCompoundDrawablesRelative(iconDrawable, null, null, null)
        }

        val err = ErrorLogManager.lastError
        if (err != null) {
            tvSummary.text = "[${err.timestamp}] ${err.summary}"
            tvDetails.text = "${err.details}\n\n--- Stacktrace ---\n${err.stackTrace}"
        } else {
            tvSummary.text = "No active errors recorded."
            tvDetails.text = "All systems operational. Telemetry engine active."
        }

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setContentView(dialogView)

        btnCloseDiagTop.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            dialog.dismiss()
        }
        btnCloseDiagBottom.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            dialog.dismiss()
        }
        btnCloseDiagBottom.background = createRoundedDrawable(
            fillColor = tc.brandBlue,
            cornerRadiusPx = 8f * density
        )

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
        val width = (resources.displayMetrics.widthPixels * 0.90f).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /**
     * Opens the full-screen Settings & Setup screen (replacing the previous popup dialog).
     */
    private fun showSettingsDialog(isFirstLaunch: Boolean) {
        val dialog = Dialog(this, R.style.Theme_HotBoi)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        dialog.setContentView(dialogView)

        val tc = getActiveThemeColors()
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(tc.bgMain))
            @Suppress("DEPRECATION")
            statusBarColor = tc.bgMain
            @Suppress("DEPRECATION")
            navigationBarColor = tc.bgMain
            @Suppress("DEPRECATION")
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            WindowCompat.getInsetsController(this, decorView).apply {
                val isNight = isNightModeActive()
                isAppearanceLightStatusBars = !isNight
                isAppearanceLightNavigationBars = !isNight
            }
        }

        val topSettingsBar = dialogView.findViewById<LinearLayout>(R.id.topSettingsBar)
        val bottomSettingsBar = dialogView.findViewById<LinearLayout>(R.id.bottomSettingsBar)
        val tvSettingsHeaderTitle = dialogView.findViewById<TextView>(R.id.tvSettingsHeaderTitle)
        val btnCancelSettings = dialogView.findViewById<Button>(R.id.btnCancelSettings)
        val btnSaveSettingsBottom = dialogView.findViewById<Button>(R.id.btnSaveSettingsBottom)

        ViewCompat.setOnApplyWindowInsetsListener(dialogView) { _, insets ->
            val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val topInset = maxOf(statusBar.top, cutout.top)
            topSettingsBar.updatePadding(
                top = topInset + dpToPx(8),
                left = maxOf(dpToPx(16), statusBar.left),
                right = maxOf(dpToPx(16), statusBar.right)
            )
            bottomSettingsBar.updatePadding(
                bottom = navBar.bottom + dpToPx(12)
            )
            insets
        }

        tvSettingsHeaderTitle.text = if (isFirstLaunch) "Welcome to HotBoi (Initial Setup)" else getString(R.string.settings)

        val etDeviceName = dialogView.findViewById<EditText>(R.id.etDeviceName)
        val spSfp = dialogView.findViewById<Spinner>(R.id.spSfpInterface)
        val etCustomSfp = dialogView.findViewById<EditText>(R.id.etCustomSfp)
        val btnScanPorts = dialogView.findViewById<Button>(R.id.btnScanPorts)

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
        val btnLoadMacKey = dialogView.findViewById<Button>(R.id.btnLoadMacKey)
        val btnTest = dialogView.findViewById<Button>(R.id.btnTestConnection)

        val spPing = dialogView.findViewById<Spinner>(R.id.spPingTarget)
        val etCustomPing = dialogView.findViewById<EditText>(R.id.etCustomPingTarget)

        val cbBg = dialogView.findViewById<CheckBox>(R.id.cbBackgroundSync)
        val spInterval = dialogView.findViewById<Spinner>(R.id.spInterval)
        val etThreshold = dialogView.findViewById<EditText>(R.id.etThreshold)
        val tvThresholdUnit = dialogView.findViewById<TextView>(R.id.tvThresholdUnit)
        val tvThresholdConverted = dialogView.findViewById<TextView>(R.id.tvThresholdConverted)
        val btnThresholdInfo = dialogView.findViewById<View>(R.id.btnThresholdInfo)
        val btnTestNotification = dialogView.findViewById<Button>(R.id.btnTestNotification)

        val rgTempUnit = dialogView.findViewById<RadioGroup>(R.id.rgTempUnit)
        val rbUnitCelsius = dialogView.findViewById<RadioButton>(R.id.rbUnitCelsius)
        val rbUnitFahrenheit = dialogView.findViewById<RadioButton>(R.id.rbUnitFahrenheit)

        val rgThemeMode = dialogView.findViewById<RadioGroup>(R.id.rgThemeMode)
        val rbThemeSystem = dialogView.findViewById<RadioButton>(R.id.rbThemeSystem)
        val rbThemeLight = dialogView.findViewById<RadioButton>(R.id.rbThemeLight)
        val rbThemeDark = dialogView.findViewById<RadioButton>(R.id.rbThemeDark)

        // 1. Hardware & Universal SFP+ Interface Selector (with live SSH port scanner)
        etDeviceName.setText(prefs.getString(SshCollector.KEY_DEVICE_NAME, SshCollector.DEFAULT_DEVICE_NAME))

        data class PortEntry(val iface: String, val displayLabel: String, val shortLabel: String, val isCustom: Boolean = false)

        val portEntries = mutableListOf(
            PortEntry("eth6", "Port 7 SFP+ (eth6)", "Port 7 SFP+ (eth6)"),
            PortEntry("eth7", "Port 8 SFP+ (eth7)", "Port 8 SFP+ (eth7)"),
            PortEntry("eth8", "Port 9 SFP+ (eth8)", "Port 9 SFP+ (eth8)"),
            PortEntry("eth9", "Port 10 SFP+ (eth9)", "Port 10 SFP+ (eth9)"),
            PortEntry("eth10", "Port 11 SFP+ (eth10)", "Port 11 SFP+ (eth10)"),
            PortEntry("sfp1", "SFP+ Port 1 (sfp1)", "SFP+ Port 1 (sfp1)"),
            PortEntry("custom", "Custom Interface Name...", "Custom SFP+", isCustom = true)
        )

        fun refreshPortSpinner(selectedIface: String) {
            val labels = portEntries.map { it.displayLabel }
            val sfpAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
            spSfp.adapter = sfpAdapter
            val matchIdx = portEntries.indexOfFirst { !it.isCustom && it.iface == selectedIface }
            if (matchIdx >= 0) {
                spSfp.setSelection(matchIdx)
                etCustomSfp.visibility = View.GONE
            } else {
                val customIdx = portEntries.indexOfFirst { it.isCustom }
                spSfp.setSelection(if (customIdx >= 0) customIdx else portEntries.lastIndex)
                etCustomSfp.visibility = View.VISIBLE
                etCustomSfp.setText(selectedIface)
            }
        }

        val savedIface = prefs.getString(SshCollector.KEY_SFP_INTERFACE, SshCollector.DEFAULT_SFP_INTERFACE) ?: SshCollector.DEFAULT_SFP_INTERFACE
        refreshPortSpinner(savedIface)

        spSfp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val entry = portEntries.getOrNull(position)
                etCustomSfp.visibility = if (entry?.isCustom == true) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Live SSH Gateway Port Scanner
        btnScanPorts.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            val host = etHost.text.toString().trim().ifEmpty { SshCollector.DEFAULT_HOST }
            val port = etPort.text.toString().trim().toIntOrNull() ?: SshCollector.DEFAULT_PORT
            val user = etUser.text.toString().trim().ifEmpty { SshCollector.DEFAULT_USER }
            val pass = etPassword.text.toString()
            val key = etSshKey.text.toString().trim()

            prefs.edit()
                .putString(SshCollector.KEY_GATEWAY_HOST, host)
                .putInt(SshCollector.KEY_GATEWAY_PORT, port)
                .putString(SshCollector.KEY_GATEWAY_USER, user)
                .putString(SshCollector.KEY_GATEWAY_PASSWORD, CryptoHelper.encrypt(pass))
                .putString(SshCollector.KEY_GATEWAY_SSH_KEY, CryptoHelper.encrypt(key))
                .apply()

            btnScanPorts.isEnabled = false
            btnScanPorts.text = "Scanning..."

            lifecycleScope.launch(Dispatchers.IO) {
                val res = SshCollector.discoverPorts(this@MainActivity)
                withContext(Dispatchers.Main) {
                    btnScanPorts.isEnabled = true
                    btnScanPorts.text = "Scan Gateway Ports"
                    if (res.isSuccess) {
                        val discovered = res.getOrThrow()
                        if (discovered.isNotEmpty()) {
                            portEntries.clear()
                            for (dp in discovered) {
                                val shortLbl = if (dp.hasOpticDom) "${dp.iface} (Optical SFP+)" else "${dp.iface} (${dp.operState.uppercase(Locale.US)})"
                                portEntries.add(PortEntry(dp.iface, dp.toDisplayLabel(), shortLbl))
                            }
                            portEntries.add(PortEntry("custom", "Custom Interface Name...", "Custom SFP+", isCustom = true))
                            val preferred = discovered.firstOrNull { it.hasOpticDom }?.iface ?: savedIface
                            refreshPortSpinner(preferred)
                            Toast.makeText(
                                this@MainActivity,
                                "Discovered ${discovered.size} interfaces on $host",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            Toast.makeText(this@MainActivity, "Connected, but no eth/sfp interfaces found", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "Port scan failed: ${res.exceptionOrNull()?.localizedMessage ?: "Check SSH credentials"}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        // 2. Data Collection Mode Switching
        val currentMode = prefs.getString(SshCollector.KEY_COLLECTION_MODE, SshCollector.MODE_SSH)
        var macUrlDraft = prefs.getString(SshCollector.KEY_MAC_SERVER_URL, SshCollector.DEFAULT_MAC_SERVER_URL) ?: SshCollector.DEFAULT_MAC_SERVER_URL
        var winUrlDraft = prefs.getString(SshCollector.KEY_WIN_SERVER_URL, SshCollector.DEFAULT_WIN_SERVER_URL) ?: SshCollector.DEFAULT_WIN_SERVER_URL
        var linuxUrlDraft = prefs.getString(SshCollector.KEY_LINUX_SERVER_URL, SshCollector.DEFAULT_LINUX_SERVER_URL) ?: SshCollector.DEFAULT_LINUX_SERVER_URL
        var activeServerModeInDialog: String? = null

        fun captureCurrentServerDraft() {
            val currentInput = etServerUrl.text?.toString()?.trim().orEmpty()
            if (currentInput.isEmpty()) return
            when (activeServerModeInDialog) {
                SshCollector.MODE_MAC_SERVER -> macUrlDraft = currentInput
                SshCollector.MODE_WIN_SERVER -> winUrlDraft = currentInput
                SshCollector.MODE_LINUX_SERVER -> linuxUrlDraft = currentInput
            }
        }

        fun configureServerSection(modeKey: String, title: String, note: String, url: String) {
            sectionSsh.visibility = View.GONE
            sectionServer.visibility = View.VISIBLE
            tvServerTitle.text = title
            tvServerBetaNote.text = note
            activeServerModeInDialog = modeKey
            etServerUrl.setText(url)
        }

        when (currentMode) {
            SshCollector.MODE_MAC_SERVER -> {
                rbMacServer.isChecked = true
                configureServerSection(
                    SshCollector.MODE_MAC_SERVER,
                    "MACOS BACKGROUND SERVER (BETA)",
                    "HotBoi will fetch continuous telemetry from your Mac daemon at 192.168.0.134:8080.",
                    macUrlDraft
                )
            }
            SshCollector.MODE_WIN_SERVER -> {
                rbWinServer.isChecked = true
                configureServerSection(
                    SshCollector.MODE_WIN_SERVER,
                    "WINDOWS BACKGROUND SERVER (BETA)",
                    "HotBoi will fetch continuous telemetry from your Windows service at 192.168.0.171:8080.",
                    winUrlDraft
                )
            }
            SshCollector.MODE_LINUX_SERVER -> {
                rbLinuxServer.isChecked = true
                configureServerSection(
                    SshCollector.MODE_LINUX_SERVER,
                    "LINUX BACKGROUND SERVER (BETA)",
                    "HotBoi will fetch continuous telemetry from your Debian / Ubuntu server daemon at 192.168.0.150:8080.",
                    linuxUrlDraft
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
            captureCurrentServerDraft()
            when (checkedId) {
                R.id.rbModeDirectSsh -> {
                    activeServerModeInDialog = null
                    sectionSsh.visibility = View.VISIBLE
                    sectionServer.visibility = View.GONE
                }
                R.id.rbModeMacServer -> {
                    configureServerSection(
                        SshCollector.MODE_MAC_SERVER,
                        "MACOS BACKGROUND SERVER (BETA)",
                        "HotBoi will fetch continuous telemetry from your Mac daemon at 192.168.0.134:8080.",
                        macUrlDraft
                    )
                }
                R.id.rbModeWinServer -> {
                    configureServerSection(
                        SshCollector.MODE_WIN_SERVER,
                        "WINDOWS BACKGROUND SERVER (BETA)",
                        "HotBoi will fetch continuous telemetry from your Windows service at 192.168.0.171:8080.",
                        winUrlDraft
                    )
                }
                R.id.rbModeLinuxServer -> {
                    configureServerSection(
                        SshCollector.MODE_LINUX_SERVER,
                        "LINUX BACKGROUND SERVER (BETA)",
                        "HotBoi will fetch continuous telemetry from your Debian / Ubuntu server daemon at 192.168.0.150:8080.",
                        linuxUrlDraft
                    )
                }
                R.id.rbModeOffline -> {
                    activeServerModeInDialog = null
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
        etPassword.setText(CryptoHelper.decrypt(prefs.getString(SshCollector.KEY_GATEWAY_PASSWORD, "")))
        etSshKey.setText(CryptoHelper.decrypt(prefs.getString(SshCollector.KEY_GATEWAY_SSH_KEY, "")))
        cbBg.isChecked = prefs.getBoolean(SshCollector.KEY_BG_ENABLED, false)

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
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            checkAndRequestNotificationPermission()
            val entered = etThreshold.text.toString().trim().toFloatOrNull() ?: SshCollector.DEFAULT_ALERT_THRESHOLD.toFloat()
            val thresholdC = if (rbUnitFahrenheit.isChecked) (entered - 32f) * 5f / 9f else entered
            val sample = TelemetryRepository.getLatestSample(this) ?: TelemetrySample(
                timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()),
                opticTemp = 42.8,
                cpu0Temp = 57.0,
                cpu1Temp = 55.4,
                ploam = "O5.1",
                rxPower = -22.1,
                txPower = 4.5,
                biasMa = 14.0,
                pingMs = 7.0
            )
            NotificationHelper.sendThermalAlert(this, sample, thresholdC.toDouble(), isTest = true)
            Toast.makeText(this, "Push notification sent! Check your notification shade.", Toast.LENGTH_SHORT).show()
        }

        // Circular Info button for notification architecture breakdown
        btnThresholdInfo.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Push Notifications Architecture")
                .setMessage(
                    "Direct SSH Mode:\n" +
                    "On-device thermal trip alerts trigger directly on this phone via background workers.\n\n" +
                    "macOS, Windows, & Linux Server Modes:\n" +
                    "Telemetry collection and critical alerts run 24/7 on your dedicated host service even when your phone is asleep, closed, or off-network."
                )
                .setPositiveButton("Got It", null)
                .show()
        }

        // Load Authorized Key / Paste from Clipboard
        btnLoadMacKey.text = "Paste from Clipboard"
        btnLoadMacKey.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clipText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
            if (!clipText.isNullOrEmpty()) {
                etSshKey.setText(clipText)
                Toast.makeText(this, "Pasted SSH key from clipboard", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
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
                .putString(SshCollector.KEY_GATEWAY_PASSWORD, CryptoHelper.encrypt(pass))
                .putString(SshCollector.KEY_GATEWAY_SSH_KEY, CryptoHelper.encrypt(key))
                .apply()

            lifecycleScope.launch(Dispatchers.IO) {
                val res = SshCollector.testConnection(this@MainActivity)
                withContext(Dispatchers.Main) {
                    btnTest.isEnabled = true
                    btnTest.text = "Test Connection"
                    if (res.isSuccess) {
                        Toast.makeText(this@MainActivity, res.getOrThrow(), Toast.LENGTH_LONG).show()
                    } else {
                        showErrorDetailsDialog()
                    }
                }
            }
        }

        fun saveAndApplySettings() {
            val chosenMode = when {
                rbMacServer.isChecked -> SshCollector.MODE_MAC_SERVER
                rbWinServer.isChecked -> SshCollector.MODE_WIN_SERVER
                rbLinuxServer.isChecked -> SshCollector.MODE_LINUX_SERVER
                rbOffline.isChecked -> SshCollector.MODE_OFFLINE
                else -> SshCollector.MODE_SSH
            }

            val devName = etDeviceName.text.toString().trim().ifEmpty { SshCollector.DEFAULT_DEVICE_NAME }
            val selectedEntry = portEntries.getOrNull(spSfp.selectedItemPosition)
            val (chosenIface, ifaceLabel) = if (selectedEntry == null || selectedEntry.isCustom) {
                val c = etCustomSfp.text.toString().trim().ifEmpty { "eth6" }
                Pair(c, "$c SFP+")
            } else {
                Pair(selectedEntry.iface, selectedEntry.shortLabel)
            }

            val host = etHost.text.toString().trim().ifEmpty { SshCollector.DEFAULT_HOST }
            val port = etPort.text.toString().trim().toIntOrNull() ?: SshCollector.DEFAULT_PORT
            val user = etUser.text.toString().trim().ifEmpty { SshCollector.DEFAULT_USER }
            val pass = etPassword.text.toString()
            val key = etSshKey.text.toString().trim()
            captureCurrentServerDraft()

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
                .putString(SshCollector.KEY_GATEWAY_PASSWORD, CryptoHelper.encrypt(pass))
                .putString(SshCollector.KEY_GATEWAY_SSH_KEY, CryptoHelper.encrypt(key))
                .putString(SshCollector.KEY_MAC_SERVER_URL, macUrlDraft)
                .putString(SshCollector.KEY_WIN_SERVER_URL, winUrlDraft)
                .putString(SshCollector.KEY_LINUX_SERVER_URL, linuxUrlDraft)
                .apply {
                    when (chosenMode) {
                        SshCollector.MODE_WIN_SERVER -> putString(SshCollector.KEY_SERVER_URL, winUrlDraft)
                        SshCollector.MODE_LINUX_SERVER -> putString(SshCollector.KEY_SERVER_URL, linuxUrlDraft)
                        SshCollector.MODE_MAC_SERVER -> putString(SshCollector.KEY_SERVER_URL, macUrlDraft)
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

            isShowingFirstLaunchDialog = false
            hasPerformedInitialRefresh = true
            updateUnitButtonLabel()
            updateStatusBadgeForMode()
            HotBoiApp.rescheduleBackgroundWork(this)

            if (oldTheme != chosenTheme) {
                when (chosenTheme) {
                    SshCollector.THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                    SshCollector.THEME_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                    else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                }
                applyNativeThemeColors()
            }

            dialog.dismiss()
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()

            hasPromptedPingMismatch = false
            injectTelemetryIntoWebView()
            triggerDataRefresh(isSilent = false)
        }

        btnSaveSettingsBottom.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            saveAndApplySettings()
        }
        btnCancelSettings.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            isShowingFirstLaunchDialog = false
            dialog.dismiss()
        }
        dialog.setOnCancelListener {
            isShowingFirstLaunchDialog = false
        }

        dialog.show()
    }

    private var adView: com.google.android.gms.ads.AdView? = null

    private fun setupAds() {
        if (!BuildConfig.SHOW_ADS) return
        try {
            val banner = com.google.android.gms.ads.AdView(this).apply {
                setAdSize(com.google.android.gms.ads.AdSize.BANNER)
                // Google's official Test Ad Unit ID for banners
                adUnitId = "ca-app-pub-3940256099942544/6300978111"
            }
            adView = banner
            binding.adContainer.removeAllViews()
            binding.adContainer.addView(banner)
            binding.adContainer.visibility = View.VISIBLE
            val adRequest = com.google.android.gms.ads.AdRequest.Builder().build()
            banner.loadAd(adRequest)
        } catch (e: Exception) {
            Log.e("HotBoi", "Failed to load AdView", e)
        }
    }

    override fun onResume() {
        super.onResume()
        adView?.resume()
        updateUnitButtonLabel()
        applyNativeThemeColors()
        refreshHandler.postDelayed(autoRefreshRunnable, AUTO_REFRESH_INTERVAL_MS)
    }

    override fun onPause() {
        super.onPause()
        adView?.pause()
        refreshHandler.removeCallbacks(autoRefreshRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            adView?.destroy()
            adView = null
        } catch (e: Exception) {
            Log.e("HotBoi", "Failed to destroy AdView", e)
        }
    }
}
