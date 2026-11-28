package com.medusko.hotboi

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
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
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
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
import androidx.core.content.edit
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.medusko.hotboi.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var assetLoader: WebViewAssetLoader
    private val refreshHandler = Handler(Looper.getMainLooper())
    private var isPageLoaded = false
    private var loadedCacheKey: String? = null
    private var hasPromptedPingMismatch = false
    private var hasPerformedInitialRefresh = false
    private var isShowingFirstLaunchDialog = false

    companion object {
        private const val ASSET_ORIGIN = "https://appassets.androidplatform.net"
        private const val LOCAL_DASHBOARD_URL = "$ASSET_ORIGIN/assets/dashboard.html"
        private const val AUTO_REFRESH_INTERVAL_MS = 60_000L
        private const val KEY_FIRST_LAUNCH = "first_launch_done"
    }

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                Toast.makeText(this, "Push notifications enabled", Toast.LENGTH_SHORT).show()
            }
        }

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
        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        if (prefs.getString(SshCollector.KEY_DEVICE_NAME, null) == "UniFi Cloud Gateway Fiber (White Bean)") {
            prefs.edit { putString(SshCollector.KEY_DEVICE_NAME, SshCollector.DEFAULT_DEVICE_NAME) }
        }

        checkAndRequestNotificationPermission()
        setupWindowInsets()
        setupBackNavigation()
        setupWebView()
        setupListeners()
        applyNativeThemeColors()
        updateStatusBadgeForMode()
        setupAds()

        binding.webView.loadUrl(LOCAL_DASHBOARD_URL)

        if (!prefs.getBoolean(KEY_FIRST_LAUNCH, false)) {
            isShowingFirstLaunchDialog = true
            prefs.edit { putBoolean(KEY_FIRST_LAUNCH, true) }
            showSettingsDialog(isFirstLaunch = true)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyNativeThemeColors()
        updateWebViewDisplayPreferences()
    }

    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { _, insets ->
            val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val displayCutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())

            val topInset = maxOf(statusBar.top, displayCutout.top)

            binding.topBar.updatePadding(
                top = topInset + 4,
                left = maxOf(dpToPx(12), statusBar.left),
                right = maxOf(dpToPx(12), statusBar.right)
            )
            binding.webView.updatePadding(
                bottom = navBar.bottom
            )
            insets
        }
    }

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
     * Configures hardened Android WebView served over virtual HTTPS [WebViewAssetLoader] origin.
     * Blocks all file://, content://, mixed-content, and external network requests inside the WebView.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = binding.webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        binding.webView.setBackgroundColor(Color.TRANSPARENT)

        binding.webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                if (BuildConfig.DEBUG) {
                    Log.d("HotBoiJS", "${consoleMessage?.message()} -- line ${consoleMessage?.lineNumber()}")
                }
                return true
            }
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url ?: return null
                val intercepted = assetLoader.shouldInterceptRequest(url)
                if (intercepted != null) return intercepted
                // Block all external network fetches inside the dashboard WebView
                return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return true
                return url.host != "appassets.androidplatform.net"
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                isPageLoaded = true
                loadedCacheKey = null
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
                if (request?.isForMainFrame == true) {
                    binding.swipeRefresh.isRefreshing = false
                    binding.progressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun postJsonMessageToWebView(jsonPayload: String, fallbackJsCall: String) {
        if (!isPageLoaded || isFinishing || isDestroyed) return
        if (WebViewFeature.isFeatureSupported(WebViewFeature.POST_WEB_MESSAGE)) {
            WebViewCompat.postWebMessage(
                binding.webView,
                WebMessageCompat(jsonPayload),
                Uri.parse(ASSET_ORIGIN)
            )
        } else {
            binding.webView.evaluateJavascript(fallbackJsCall, null)
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

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
        val cobaltBlue: Int,
        val cyan: Int,
        val amber: Int,
        val alertRed: Int,
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
                cobaltBlue = Color.parseColor("#2E6BD9"),
                cyan = Color.parseColor("#60A5FA"),
                amber = Color.parseColor("#FBBF24"),
                alertRed = Color.parseColor("#FF5252"),
                textPrimary = Color.parseColor("#F1F3F8"),
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
                cobaltBlue = Color.parseColor("#2E6BD9"),
                cyan = Color.parseColor("#2E6BD9"),
                amber = Color.parseColor("#B45309"),
                alertRed = Color.parseColor("#DC2626"),
                textPrimary = Color.parseColor("#1E1A16"),
                textSecondary = Color.parseColor("#6B6358"),
                textMuted = Color.parseColor("#948B7D")
            )
        }
    }

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

        binding.divider.setBackgroundColor(tc.cardBorder)
        binding.topBarDivider.setBackgroundColor(tc.cardBorder)

        binding.swipeRefresh.setColorSchemeColors(tc.brandBlue)
        binding.swipeRefresh.setProgressBackgroundColorSchemeColor(tc.cardBg)

        binding.tvAppTitle.setTextColor(tc.textPrimary)
        binding.btnUnitToggle.setTextColor(tc.brandBlue)

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
        updateStatusBadgeForMode()
    }

    private fun updateThemeIcon() {
        val isNight = isNightModeActive()
        binding.ivThemeIcon.setImageResource(
            if (isNight) R.drawable.ic_dark_mode else R.drawable.ic_light_mode
        )
    }

    private fun toggleTheme() {
        val isNight = isNightModeActive()
        val newTheme = if (isNight) SshCollector.THEME_LIGHT else SshCollector.THEME_DARK
        prefs.edit { putString(SshCollector.KEY_THEME_MODE, newTheme) }

        when (newTheme) {
            SshCollector.THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            SshCollector.THEME_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        }
        applyNativeThemeColors()
        updateWebViewDisplayPreferences()
        HotBoiWidgetProvider.updateAllWidgets(this)
        Toast.makeText(
            this,
            if (newTheme == SshCollector.THEME_DARK) "Dark Mode activated" else "Light Mode activated",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateUnitButtonLabel() {
        val current = prefs.getString(SshCollector.KEY_TEMP_UNIT, SshCollector.UNIT_CELSIUS) ?: SshCollector.UNIT_CELSIUS
        binding.btnUnitToggle.text = if (current == SshCollector.UNIT_FAHRENHEIT) "°F" else "°C"
    }

    private fun setStatusBadgeContent(label: String, iconRes: Int, colorInt: Int) {
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

    private fun updateStatusBadgeForMode() {
        val tc = getActiveThemeColors()
        val mode = prefs.getString(SshCollector.KEY_COLLECTION_MODE, SshCollector.MODE_SSH) ?: SshCollector.MODE_SSH
        if (binding.btnErrorLog.visibility == View.VISIBLE && mode != SshCollector.MODE_OFFLINE) {
            val currentLabel = binding.tvStatus.text?.toString().orEmpty()
            val errLabel = if (currentLabel.startsWith("Error") || currentLabel.startsWith("No Module")) currentLabel else "Error"
            setStatusBadgeContent(errLabel, R.drawable.ic_warning, tc.alertRed)
            return
        }
        when (mode) {
            SshCollector.MODE_SSH -> {
                val sfpIface = prefs.getString(SshCollector.KEY_SFP_INTERFACE, SshCollector.DEFAULT_SFP_INTERFACE) ?: SshCollector.DEFAULT_SFP_INTERFACE
                val lbl = if (binding.tvStatus.text?.toString()?.contains("(") == true) "SSH ($sfpIface)" else "Direct SSH"
                setStatusBadgeContent(lbl, R.drawable.ic_mode_ssh, tc.brandBlue)
            }
            SshCollector.MODE_MAC_SERVER -> setStatusBadgeContent("macOS", R.drawable.ic_os_macos, tc.brandBlue)
            SshCollector.MODE_WIN_SERVER -> setStatusBadgeContent("Windows", R.drawable.ic_os_windows, tc.brandBlue)
            SshCollector.MODE_LINUX_SERVER -> setStatusBadgeContent("Linux", R.drawable.ic_os_linux, tc.brandBlue)
            SshCollector.MODE_SERVER -> setStatusBadgeContent("Server", R.drawable.ic_mode_server, tc.brandBlue)
            else -> setStatusBadgeContent("Offline", R.drawable.ic_mode_offline, tc.textSecondary)
        }
    }

    private fun setupListeners() {
        updateUnitButtonLabel()
        updateThemeIcon()

        binding.btnUnitToggle.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            val current = prefs.getString(SshCollector.KEY_TEMP_UNIT, SshCollector.UNIT_CELSIUS) ?: SshCollector.UNIT_CELSIUS
            val newUnit = if (current == SshCollector.UNIT_CELSIUS) SshCollector.UNIT_FAHRENHEIT else SshCollector.UNIT_CELSIUS
            prefs.edit { putString(SshCollector.KEY_TEMP_UNIT, newUnit) }
            updateUnitButtonLabel()
            updateWebViewDisplayPreferences()
            HotBoiWidgetProvider.updateAllWidgets(this)
            val label = if (newUnit == SshCollector.UNIT_FAHRENHEIT) "Switched to Fahrenheit (°F)" else "Switched to Celsius (°C)"
            Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
        }

        binding.btnAbout.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            showAboutHotBoiDialog()
        }

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

    private fun updateWebViewDisplayPreferences() {
        if (!isPageLoaded) {
            injectTelemetryIntoWebView()
            return
        }
        val unit = prefs.getString(SshCollector.KEY_TEMP_UNIT, SshCollector.UNIT_CELSIUS) ?: SshCollector.UNIT_CELSIUS
        val isLight = !isNightModeActive()
        val msg = JSONObject().apply {
            put("type", "prefs")
            put("tempUnit", unit)
            put("isLight", isLight)
        }.toString()
        postJsonMessageToWebView(msg, "window.applyDisplayPreferences('$unit', $isLight);")
    }

    /**
     * Sends full historical bundle to WebView (used on initial load or when mode/interface changes).
     */
    private fun injectTelemetryIntoWebView() {
        val mode = prefs.getString(SshCollector.KEY_COLLECTION_MODE, SshCollector.MODE_SSH) ?: SshCollector.MODE_SSH
        val targetCacheKey = TelemetryRepository.resolveCacheKey(this, mode)
        lifecycleScope.launch(Dispatchers.IO) {
            val bundleJson = TelemetryRepository.toJsonBundle(this@MainActivity, mode)
            withContext(Dispatchers.Main) {
                loadedCacheKey = targetCacheKey
                postJsonMessageToWebView(bundleJson, "window.renderDashboardWithData($bundleJson);")
            }
        }
    }

    /**
     * Sends a ~300-byte single-sample incremental delta when [loadedCacheKey] matches,
     * falling back to full bundle injection only if the active mode or SFP+ interface changed.
     */
    private fun pushSampleUpdateToWebView(sample: TelemetrySample, mode: String) {
        val targetCacheKey = TelemetryRepository.resolveCacheKey(this, mode)
        if (!isPageLoaded || loadedCacheKey != targetCacheKey) {
            injectTelemetryIntoWebView()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val deltaJson = TelemetryRepository.toIncrementalDeltaJson(this@MainActivity, sample, mode)
            withContext(Dispatchers.Main) {
                postJsonMessageToWebView(deltaJson, "window.appendTelemetrySample($deltaJson);")
            }
        }
    }

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
                    val result = SshCollector.collectViaSsh(this@MainActivity, usePool = true)
                    if (result.isSuccess) {
                        val sample = result.getOrThrow()
                        TelemetryRepository.addSample(this@MainActivity, sample, SshCollector.MODE_SSH)
                    }

                    withContext(Dispatchers.Main) {
                        binding.progressBar.visibility = View.GONE
                        binding.swipeRefresh.isRefreshing = false

                        if (result.isSuccess) {
                            val sample = result.getOrThrow()
                            pushSampleUpdateToWebView(sample, SshCollector.MODE_SSH)
                            NotificationHelper.checkThresholdAndAlert(this@MainActivity, sample)
                            HotBoiWidgetProvider.updateAllWidgets(this@MainActivity)

                            binding.btnErrorLog.visibility = View.GONE
                            setStatusBadgeContent("SSH ($sfpIface)", R.drawable.ic_mode_ssh, getActiveThemeColors().brandBlue)
                        } else {
                            val err = result.exceptionOrNull()
                            val msg = err?.message ?: "Unknown SSH Error"
                            ErrorLogManager.logError("SSH Direct Probe ($host • $sfpIface)", err ?: Exception(msg))

                            injectTelemetryIntoWebView()

                            binding.btnErrorLog.visibility = View.VISIBLE
                            val badgeLabel = if (msg.contains("No SFP+ transceiver", ignoreCase = true)) {
                                "No Module ($sfpIface)"
                            } else {
                                "Error (SSH)"
                            }
                            setStatusBadgeContent(badgeLabel, R.drawable.ic_warning, getActiveThemeColors().alertRed)

                            if (!isSilent) {
                                showErrorDetailsDialog()
                            }
                        }
                    }
                }
            }

            SshCollector.MODE_MAC_SERVER, SshCollector.MODE_WIN_SERVER, SshCollector.MODE_LINUX_SERVER, SshCollector.MODE_SERVER -> {
                val serverName = when (mode) {
                    SshCollector.MODE_MAC_SERVER -> "macOS"
                    SshCollector.MODE_WIN_SERVER -> "Windows"
                    SshCollector.MODE_LINUX_SERVER -> "Linux"
                    else -> "Server"
                }
                val serverUrl = CollectorHttpClient.resolveServerUrlForMode(this, mode)

                lifecycleScope.launch(Dispatchers.IO) {
                    val fetchRes = CollectorHttpClient.fetchTelemetrySample(this@MainActivity, serverUrl)
                    if (fetchRes.isSuccess) {
                        val (validatedBase, sample) = fetchRes.getOrThrow()
                        TelemetryRepository.addSample(this@MainActivity, sample, mode)
                        withContext(Dispatchers.Main) {
                            binding.progressBar.visibility = View.GONE
                            binding.swipeRefresh.isRefreshing = false
                            pushSampleUpdateToWebView(sample, mode)
                            NotificationHelper.checkThresholdAndAlert(this@MainActivity, sample)
                            HotBoiWidgetProvider.updateAllWidgets(this@MainActivity)

                            binding.btnErrorLog.visibility = View.GONE
                            setStatusBadgeContent(serverName, R.drawable.ic_mode_server, getActiveThemeColors().brandBlue)
                        }

                        if (!hasPromptedPingMismatch) {
                            val serverPing = CollectorHttpClient.fetchServerPingConfig(this@MainActivity, validatedBase)
                            val appPing = prefs.getString(SshCollector.KEY_PING_TARGET, SshCollector.DEFAULT_PING_TARGET) ?: SshCollector.DEFAULT_PING_TARGET
                            if (!serverPing.isNullOrEmpty() && serverPing != appPing) {
                                withContext(Dispatchers.Main) {
                                    hasPromptedPingMismatch = true
                                    showPingTargetMismatchDialog(validatedBase, serverName, serverPing, appPing)
                                }
                            }
                        }
                    } else {
                        val err = fetchRes.exceptionOrNull() ?: Exception("Unknown server error")
                        ErrorLogManager.logError("$serverName Probe ($serverUrl)", err)
                        withContext(Dispatchers.Main) {
                            binding.progressBar.visibility = View.GONE
                            binding.swipeRefresh.isRefreshing = false
                            binding.btnErrorLog.visibility = View.VISIBLE
                            setStatusBadgeContent("Error ($serverName)", R.drawable.ic_warning, getActiveThemeColors().alertRed)

                            if (!isSilent) {
                                showErrorDetailsDialog()
                            }
                        }
                    }
                }
            }

            else -> {
                injectTelemetryIntoWebView()
                HotBoiWidgetProvider.updateAllWidgets(this)
                binding.btnErrorLog.visibility = View.GONE
                setStatusBadgeContent("Offline", R.drawable.ic_mode_offline, getActiveThemeColors().textSecondary)
                binding.swipeRefresh.isRefreshing = false
                binding.progressBar.visibility = View.GONE
            }
        }
    }

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
                    val pushRes = CollectorHttpClient.pushServerPingConfig(this@MainActivity, serverBase, appPing)
                    withContext(Dispatchers.Main) {
                        if (pushRes.isSuccess) {
                            val code = pushRes.getOrThrow()
                            if (code in 200..299) {
                                Toast.makeText(this@MainActivity, "Successfully pushed $appPing to $serverName", Toast.LENGTH_LONG).show()
                                triggerDataRefresh(isSilent = true)
                            } else {
                                Toast.makeText(this@MainActivity, "Server returned HTTP $code", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(this@MainActivity, "Push failed: ${pushRes.exceptionOrNull()?.localizedMessage}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("Inherit from Server ($serverPing)") { _, _ ->
                prefs.edit {
                    putString(SshCollector.KEY_PING_TARGET, serverPing)
                    putString(SshCollector.KEY_PING_TARGET_LABEL, serverPing)
                }
                injectTelemetryIntoWebView()
                Toast.makeText(this@MainActivity, "Inherited $serverPing from $serverName", Toast.LENGTH_LONG).show()
            }
            .setNeutralButton("Keep Separate", null)
            .show()
    }

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
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleRow.addView(titleText)

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

        val scrollView = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

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
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
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
            setTextColor(tc.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
        })
        identityTitleRow.addView(TextView(this).apply {
            text = "RC3"
            setTextColor(tc.amber)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dpToPx(6), dpToPx(1), dpToPx(6), dpToPx(1))
            background = createRoundedDrawable(
                fillColor = tc.cardBg,
                cornerRadiusPx = 5f * density,
                strokeWidthPx = strokePx,
                strokeColor = tc.amber
            )
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
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

        scrollContent.addView(TextView(this).apply {
            text = "About"
            setTextColor(tc.cyan)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
        })

        scrollContent.addView(TextView(this).apply {
            text = "HotBoi is a standalone on-device thermal and optical telemetry monitor for network gateways, switches, and SFP+ / SFP28 optical transceivers.\n\n" +
                "Designed for network engineers and homelab operators, HotBoi connects directly from your Android device over pooled SSH (Ed25519 / RSA / password) or authenticated host collector servers to stream live laser optics thermals, dual-core CPU temperatures, optical Rx/Tx signal power (dBm), laser bias current (mA), and ICMP round-trip ping latency with automated threshold alerts."
            setTextColor(tc.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setLineSpacing(dpToPx(3).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpToPx(6)
            }
        })

        scrollContent.addView(TextView(this).apply {
            text = "Release Candidate 3 & What's New"
            setTextColor(tc.cyan)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpToPx(14)
            }
        })

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
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpToPx(6)
            }
        }

        whatsNewBox.addView(TextView(this).apply {
            text = "• Medusko Home Screen Thermal Widget with live Optic Temp, CPU Temp, Rx dBm, Ping & 1-tap refresh\n" +
                "• Unified Medusko Cobalt Blue, Warm Gold, Dark Obsidian & Warm Linen palette matching Keyboardy & Lagatron\n" +
                "• Pooled SSH session engine + pure POSIX shell probe (~150 ms polls, zero python3 gateway requirement)\n" +
                "• Hardened TOFU SSH host-key pinning, fail-closed AES-256-GCM Keystore encryption & WAN HTTPS enforcement\n" +
                "• Append-only JSONL storage + WebViewAssetLoader & WebMessageCompat O(1) incremental chart updates\n" +
                "• R8 full-mode optimization & stripped PQC tables reducing release APK footprint to 4.6 MB"
            setTextColor(tc.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setLineSpacing(dpToPx(3).toFloat(), 1f)
        })
        scrollContent.addView(whatsNewBox)

        scrollContent.addView(TextView(this).apply {
            text = "Links & Resources"
            setTextColor(tc.cyan)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpToPx(14)
                bottomMargin = dpToPx(6)
            }
        })

        fun createLinkButton(label: String, url: String): TextView {
            return TextView(this).apply {
                text = label
                setTextColor(tc.cyan)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dpToPx(8), dpToPx(7), dpToPx(8), dpToPx(7))
                background = createRoundedDrawable(
                    fillColor = tc.bgDark,
                    cornerRadiusPx = 8f * density,
                    strokeWidthPx = strokePx,
                    strokeColor = tc.cardBorder
                )
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
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
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        linksRow1.addView(createLinkButton("Changelog ↗", "https://github.com/medusko/HotBoi/blob/main/CHANGELOG.md"))
        linksRow1.addView(createLinkButton("Releases ↗", "https://github.com/medusko/HotBoi/releases"))
        scrollContent.addView(linksRow1)

        val linksRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpToPx(6)
            }
        }
        linksRow2.addView(createLinkButton("Source Code ↗", "https://github.com/medusko/HotBoi"))
        linksRow2.addView(createLinkButton("Sponsor ↗", "https://github.com/sponsors/Zlatty"))
        scrollContent.addView(linksRow2)

        val contactBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
            background = createRoundedDrawable(
                fillColor = tc.bgDark,
                cornerRadiusPx = 10f * density,
                strokeWidthPx = strokePx,
                strokeColor = tc.cardBorder
            )
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpToPx(8)
            }
        }

        fun createContactRow(label: String, value: String, url: String): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dpToPx(2)
                    bottomMargin = dpToPx(2)
                }
                addView(TextView(this@MainActivity).apply {
                    text = label
                    setTextColor(tc.textSecondary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(dpToPx(72), ViewGroup.LayoutParams.WRAP_CONTENT)
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

        scrollContent.addView(TextView(this).apply {
            text = "Android is a trademark of Google LLC. All other trademarks, service marks, trade names, and product names referenced herein are the property of their respective owners. HotBoi is an independent product developed by Medusko and is not affiliated with, endorsed by, or sponsored by Google LLC or any other referenced hardware vendor. Use of third-party names or marks is for identification and compatibility purposes only and does not imply any association with or endorsement by the trademark holders."
            setTextColor(tc.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setLineSpacing(dpToPx(2).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpToPx(12)
            }
        })

        scrollContent.addView(TextView(this).apply {
            text = "HotBoi v$vName (Build $vCode) • Release Candidate 3 • Built for Android 15 / Pixel\nDesigned & Engineered by Medusko"
            setTextColor(tc.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpToPx(14)
                bottomMargin = dpToPx(4)
            }
        })

        scrollView.addView(scrollContent)
        container.addView(scrollView)

        dialog.setContentView(container)
        dialog.show()
        val width = (resources.displayMetrics.widthPixels * 0.90f).toInt()
        val maxHeight = (resources.displayMetrics.heightPixels * 0.82f).toInt()
        dialog.window?.setLayout(width, maxHeight)
    }

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
        tvActiveMode.setTextColor(tc.brandBlue)
        val iconDrawable = ContextCompat.getDrawable(this, modeIcon)?.mutate()
        if (iconDrawable != null) {
            val sizePx = dpToPx(15)
            iconDrawable.setBounds(0, 0, sizePx, sizePx)
            DrawableCompat.setTint(iconDrawable, tc.brandBlue)
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
            fillColor = tc.cobaltBlue,
            cornerRadiusPx = 8f * density
        )

        btnClear.setOnClickListener {
            ErrorLogManager.clearErrors()
            binding.btnErrorLog.visibility = View.GONE
            updateStatusBadgeForMode()
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

    private fun persistCredentialsSafely(
        host: String,
        port: Int,
        user: String,
        pass: String,
        key: String
    ): Boolean {
        val encPass = CryptoHelper.encryptOrNull(pass)
        val encKey = CryptoHelper.encryptOrNull(key)
        if (encPass == null || encKey == null) {
            Toast.makeText(this, "Android Keystore unavailable: refused to store plaintext credentials", Toast.LENGTH_LONG).show()
            return false
        }
        prefs.edit {
            putString(SshCollector.KEY_GATEWAY_HOST, host)
            putInt(SshCollector.KEY_GATEWAY_PORT, port)
            putString(SshCollector.KEY_GATEWAY_USER, user)
            putString(SshCollector.KEY_GATEWAY_PASSWORD, encPass)
            putString(SshCollector.KEY_GATEWAY_SSH_KEY, encKey)
        }
        return true
    }

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
        val etServerApiToken = dialogView.findViewById<EditText>(R.id.etServerApiToken)

        val etHost = dialogView.findViewById<EditText>(R.id.etHost)
        val etPort = dialogView.findViewById<EditText>(R.id.etPort)
        val etUser = dialogView.findViewById<EditText>(R.id.etUser)
        val etPassword = dialogView.findViewById<EditText>(R.id.etPassword)
        val etSshKey = dialogView.findViewById<EditText>(R.id.etSshKey)
        val btnLoadMacKey = dialogView.findViewById<Button>(R.id.btnLoadMacKey)
        val cbPonStickAutoIp = dialogView.findViewById<CheckBox>(R.id.cbPonStickAutoIp)
        val tvKnownHostsStatus = dialogView.findViewById<TextView>(R.id.tvKnownHostsStatus)
        val btnResetKnownHosts = dialogView.findViewById<Button>(R.id.btnResetKnownHosts)
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
        val btnAddWidget = dialogView.findViewById<Button>(R.id.btnAddWidget)

        btnAddWidget.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            requestPinThermalWidget()
        }

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

        fun refreshKnownHostsLabel() {
            lifecycleScope.launch(Dispatchers.IO) {
                val summary = SshCollector.getKnownHostsSummary(this@MainActivity)
                withContext(Dispatchers.Main) {
                    tvKnownHostsStatus.text = summary
                }
            }
        }
        refreshKnownHostsLabel()

        btnResetKnownHosts.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            AlertDialog.Builder(this)
                .setTitle("Reset Pinned SSH Host Keys?")
                .setMessage("Only clear pinned host keys if you replaced or reinstalled your gateway hardware. The next SSH connection will pin the new host key (TOFU).")
                .setPositiveButton("Reset Keys") { _, _ ->
                    if (SshCollector.clearKnownHosts(this)) {
                        refreshKnownHostsLabel()
                        Toast.makeText(this, "Cleared pinned SSH host keys", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnScanPorts.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            val host = etHost.text.toString().trim().ifEmpty { SshCollector.DEFAULT_HOST }
            val port = etPort.text.toString().trim().toIntOrNull() ?: SshCollector.DEFAULT_PORT
            val user = etUser.text.toString().trim().ifEmpty { SshCollector.DEFAULT_USER }
            val pass = etPassword.text.toString()
            val key = etSshKey.text.toString().trim()

            if (!persistCredentialsSafely(host, port, user, pass, key)) return@setOnClickListener

            btnScanPorts.isEnabled = false
            btnScanPorts.text = "Scanning..."

            lifecycleScope.launch(Dispatchers.IO) {
                val res = SshCollector.discoverPorts(this@MainActivity)
                withContext(Dispatchers.Main) {
                    btnScanPorts.isEnabled = true
                    btnScanPorts.text = "Scan Gateway Ports"
                    refreshKnownHostsLabel()
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
                            Toast.makeText(this@MainActivity, "Discovered ${discovered.size} interfaces on $host", Toast.LENGTH_SHORT).show()
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
                    "LAN HTTP (e.g. http://192.168.0.134:8080 with --bind 0.0.0.0) or remote HTTPS.",
                    macUrlDraft
                )
            }
            SshCollector.MODE_WIN_SERVER -> {
                rbWinServer.isChecked = true
                configureServerSection(
                    SshCollector.MODE_WIN_SERVER,
                    "WINDOWS BACKGROUND SERVER (BETA)",
                    "LAN HTTP (e.g. http://192.168.0.171:8080 with --bind 0.0.0.0) or remote HTTPS.",
                    winUrlDraft
                )
            }
            SshCollector.MODE_LINUX_SERVER -> {
                rbLinuxServer.isChecked = true
                configureServerSection(
                    SshCollector.MODE_LINUX_SERVER,
                    "LINUX BACKGROUND SERVER (BETA)",
                    "LAN HTTP (e.g. http://192.168.0.150:8080 with --bind 0.0.0.0) or remote HTTPS.",
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
                        "LAN HTTP (e.g. http://192.168.0.134:8080 with --bind 0.0.0.0) or remote HTTPS.",
                        macUrlDraft
                    )
                }
                R.id.rbModeWinServer -> {
                    configureServerSection(
                        SshCollector.MODE_WIN_SERVER,
                        "WINDOWS BACKGROUND SERVER (BETA)",
                        "LAN HTTP (e.g. http://192.168.0.171:8080 with --bind 0.0.0.0) or remote HTTPS.",
                        winUrlDraft
                    )
                }
                R.id.rbModeLinuxServer -> {
                    configureServerSection(
                        SshCollector.MODE_LINUX_SERVER,
                        "LINUX BACKGROUND SERVER (BETA)",
                        "LAN HTTP (e.g. http://192.168.0.150:8080 with --bind 0.0.0.0) or remote HTTPS.",
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

        val pingOptions = arrayOf("1.1.1.1 (Cloudflare)", "8.8.8.8 (Google)", "9.9.9.9 (Quad9)", "Custom Target...")
        spPing.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, pingOptions)

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

        val intervalOptions = arrayOf("Every 15 minutes", "Every 30 minutes", "Every 1 hour (Recommended)", "Every 2 hours")
        spInterval.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, intervalOptions)

        when (prefs.getInt(SshCollector.KEY_BG_INTERVAL_MIN, 60)) {
            15 -> spInterval.setSelection(0)
            30 -> spInterval.setSelection(1)
            120 -> spInterval.setSelection(3)
            else -> spInterval.setSelection(2)
        }

        etHost.setText(prefs.getString(SshCollector.KEY_GATEWAY_HOST, SshCollector.DEFAULT_HOST))
        etPort.setText(prefs.getInt(SshCollector.KEY_GATEWAY_PORT, SshCollector.DEFAULT_PORT).toString())
        etUser.setText(prefs.getString(SshCollector.KEY_GATEWAY_USER, SshCollector.DEFAULT_USER))
        etPassword.setText(CryptoHelper.decrypt(prefs.getString(SshCollector.KEY_GATEWAY_PASSWORD, "")))
        etSshKey.setText(CryptoHelper.decrypt(prefs.getString(SshCollector.KEY_GATEWAY_SSH_KEY, "")))
        etServerApiToken.setText(CryptoHelper.decrypt(prefs.getString(SshCollector.KEY_SERVER_API_TOKEN, "")))
        cbPonStickAutoIp.isChecked = prefs.getBoolean(SshCollector.KEY_PON_STICK_AUTO_IP, false)
        cbBg.isChecked = prefs.getBoolean(SshCollector.KEY_BG_ENABLED, false)

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

        when (prefs.getString(SshCollector.KEY_THEME_MODE, SshCollector.THEME_SYSTEM)) {
            SshCollector.THEME_LIGHT -> rbThemeLight.isChecked = true
            SshCollector.THEME_DARK -> rbThemeDark.isChecked = true
            else -> rbThemeSystem.isChecked = true
        }

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

        btnTest.setOnClickListener {
            val host = etHost.text.toString().trim().ifEmpty { SshCollector.DEFAULT_HOST }
            val port = etPort.text.toString().trim().toIntOrNull() ?: SshCollector.DEFAULT_PORT
            val user = etUser.text.toString().trim().ifEmpty { SshCollector.DEFAULT_USER }
            val pass = etPassword.text.toString()
            val key = etSshKey.text.toString().trim()

            if (!persistCredentialsSafely(host, port, user, pass, key)) return@setOnClickListener

            btnTest.isEnabled = false
            btnTest.text = "Testing..."

            lifecycleScope.launch(Dispatchers.IO) {
                val res = SshCollector.testConnection(this@MainActivity)
                withContext(Dispatchers.Main) {
                    btnTest.isEnabled = true
                    btnTest.text = "Test Connection"
                    refreshKnownHostsLabel()
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
            val serverToken = etServerApiToken.text.toString().trim()
            captureCurrentServerDraft()

            // Validate collector server URL security policy immediately when saving a Server mode
            if (chosenMode == SshCollector.MODE_MAC_SERVER || chosenMode == SshCollector.MODE_WIN_SERVER || chosenMode == SshCollector.MODE_LINUX_SERVER) {
                val activeUrl = when (chosenMode) {
                    SshCollector.MODE_WIN_SERVER -> winUrlDraft
                    SshCollector.MODE_LINUX_SERVER -> linuxUrlDraft
                    else -> macUrlDraft
                }
                try {
                    CollectorHttpClient.validateCollectorBaseUrl(activeUrl)
                } catch (se: SecurityException) {
                    Toast.makeText(this, se.message, Toast.LENGTH_LONG).show()
                    return
                }
            }

            val encPass = CryptoHelper.encryptOrNull(pass)
            val encKey = CryptoHelper.encryptOrNull(key)
            val encToken = CryptoHelper.encryptOrNull(serverToken)
            if (encPass == null || encKey == null || encToken == null) {
                Toast.makeText(this, "Android Keystore error: refused to store unencrypted credentials", Toast.LENGTH_LONG).show()
                return
            }

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

            prefs.edit {
                putString(SshCollector.KEY_DEVICE_NAME, devName)
                putString(SshCollector.KEY_SFP_INTERFACE, chosenIface)
                putString(SshCollector.KEY_SFP_PORT_LABEL, ifaceLabel)
                putString(SshCollector.KEY_COLLECTION_MODE, chosenMode)
                putString(SshCollector.KEY_GATEWAY_HOST, host)
                putInt(SshCollector.KEY_GATEWAY_PORT, port)
                putString(SshCollector.KEY_GATEWAY_USER, user)
                putString(SshCollector.KEY_GATEWAY_PASSWORD, encPass)
                putString(SshCollector.KEY_GATEWAY_SSH_KEY, encKey)
                putBoolean(SshCollector.KEY_PON_STICK_AUTO_IP, cbPonStickAutoIp.isChecked)
                putString(SshCollector.KEY_MAC_SERVER_URL, macUrlDraft)
                putString(SshCollector.KEY_WIN_SERVER_URL, winUrlDraft)
                putString(SshCollector.KEY_LINUX_SERVER_URL, linuxUrlDraft)
                putString(SshCollector.KEY_SERVER_API_TOKEN, encToken)
                when (chosenMode) {
                    SshCollector.MODE_WIN_SERVER -> putString(SshCollector.KEY_SERVER_URL, winUrlDraft)
                    SshCollector.MODE_LINUX_SERVER -> putString(SshCollector.KEY_SERVER_URL, linuxUrlDraft)
                    SshCollector.MODE_MAC_SERVER -> putString(SshCollector.KEY_SERVER_URL, macUrlDraft)
                }
                putString(SshCollector.KEY_PING_TARGET, chosenTarget)
                putString(SshCollector.KEY_PING_TARGET_LABEL, targetLabel)
                putBoolean(SshCollector.KEY_BG_ENABLED, cbBg.isChecked)
                putInt(SshCollector.KEY_BG_INTERVAL_MIN, intervalMin)
                putFloat(SshCollector.KEY_ALERT_THRESHOLD, thresholdC)
                putString(SshCollector.KEY_TEMP_UNIT, chosenUnit)
                putString(SshCollector.KEY_THEME_MODE, chosenTheme)
            }

            if (chosenMode != SshCollector.MODE_SSH) {
                SshCollector.closePooledSession()
            }

            isShowingFirstLaunchDialog = false
            hasPerformedInitialRefresh = true
            loadedCacheKey = null
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
            HotBoiWidgetProvider.updateAllWidgets(this)
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

    private fun requestPinThermalWidget() {
        val appWidgetManager = AppWidgetManager.getInstance(this)
        val provider = ComponentName(this, HotBoiWidgetProvider::class.java)
        if (appWidgetManager.isRequestPinAppWidgetSupported) {
            val refreshCallback = Intent(this, HotBoiWidgetProvider::class.java).apply {
                action = HotBoiWidgetProvider.ACTION_WIDGET_REFRESH
            }
            val successPi = PendingIntent.getBroadcast(
                this,
                2005,
                refreshCallback,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val requested = appWidgetManager.requestPinAppWidget(provider, null, successPi)
            if (!requested) {
                Toast.makeText(this, "Long-press your Home Screen → Widgets → HotBoi to add the widget", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, "Long-press your Home Screen → Widgets → HotBoi to add the widget", Toast.LENGTH_LONG).show()
        }
    }

    private var adView: com.google.android.gms.ads.AdView? = null

    private fun setupAds() {
        if (!BuildConfig.SHOW_ADS) return
        try {
            val banner = com.google.android.gms.ads.AdView(this).apply {
                setAdSize(com.google.android.gms.ads.AdSize.BANNER)
                adUnitId = BuildConfig.ADMOB_BANNER_UNIT_ID
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
        binding.webView.onResume()
        binding.webView.resumeTimers()
        updateUnitButtonLabel()
        applyNativeThemeColors()
        refreshHandler.postDelayed(autoRefreshRunnable, AUTO_REFRESH_INTERVAL_MS)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(autoRefreshRunnable)
        binding.webView.onPause()
        binding.webView.pauseTimers()
        adView?.pause()
    }

    override fun onStop() {
        super.onStop()
        SshCollector.closePooledSession()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            (binding.webView.parent as? ViewGroup)?.removeView(binding.webView)
            binding.webView.stopLoading()
            binding.webView.destroy()
        } catch (e: Exception) {
            Log.e("HotBoi", "Failed to destroy WebView", e)
        }
        try {
            adView?.destroy()
            adView = null
        } catch (e: Exception) {
            Log.e("HotBoi", "Failed to destroy AdView", e)
        }
    }
}
