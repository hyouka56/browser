package com.search.browser

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import com.cefrium.CefriumBrowser
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var browser: CefriumBrowser
    private lateinit var browserSlot: FrameLayout
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var downloadsPanel: ScrollView
    private lateinit var downloadsList: LinearLayout
    private lateinit var searchField: EditText
    private lateinit var shieldButton: ImageButton
    private lateinit var rendererErrorPanel: FrameLayout

    private var rendererRecoveryAttempts = 0
    private var rendererRecoveryPending = false
    private var lastRequestedUrl = "about:blank"
    private var lastSuccessfulUrl = HOME_URL
    private var testCrashRenderer = false
    private var testCrashTriggered = false
    private val preferences by lazy { getSharedPreferences(PREFERENCES, MODE_PRIVATE) }
    private val downloadRecords = LinkedHashMap<Int, DownloadRecord>()
    private val activeDownloadItems = LinkedHashMap<String, CefriumBrowser.DownloadItem>()
    private var adBlockingEnabled = true
    private var showingDownloads = false
    private var notificationPermissionRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        adBlockingEnabled = preferences.getBoolean(KEY_AD_BLOCK, true)
        lastSuccessfulUrl = preferences.getString(KEY_LAST_URL, HOME_URL).orEmpty()
            .takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: HOME_URL
        lastRequestedUrl = lastSuccessfulUrl
        restoreSavedDownloads()
        createDownloadNotificationChannel()
        buildLayout()

        testCrashRenderer = BuildConfig.DEBUG &&
            intent.getBooleanExtra(EXTRA_TEST_CRASH_RENDERER, false)
        val testUrl = if (BuildConfig.DEBUG) {
            intent.getStringExtra(EXTRA_TEST_URL)?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        val startUrl = testUrl ?: lastSuccessfulUrl
        attachBrowser(startUrl, savedInstanceState?.getBundle(KEY_BROWSER_STATE))

        Log.i(
            TAG,
            "CEF_ENGINE version=0.20.0 chromium=156.0.8078.88 " +
                "abi=" + android.os.Build.SUPPORTED_ABIS.joinToString()
        )
        updateShieldButton()
        renderDownloads()
    }

    private fun attachBrowser(initialUrl: String, savedState: Bundle? = null) {
        val instance = CefriumBrowser.createWithSurface(this)
        browser = instance
        instance.setBackgroundColor(Color.BLACK)
        instance.setAdBlockEnabled(adBlockingEnabled)
        browserSlot.addView(
            instance.getSurfaceContainer(),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        instance.setCallback(object : CefriumBrowser.Callback() {
            override fun onUrlChanged(url: String) {
                lastRequestedUrl = url
                runOnUiThread {
                    if (!searchField.hasFocus()) searchField.setText(url)
                }
                Log.i(TAG, "PAGE_URL_CHANGED url=$url")
            }

            override fun onTitleChanged(title: String) {
                Log.i(TAG, "PAGE_TITLE title=$title")
            }

            override fun onLoadingStateChanged(
                isLoading: Boolean,
                canGoBack: Boolean,
                canGoForward: Boolean
            ) {
                Log.i(
                    TAG,
                    "PAGE_LOAD_STATE loading=$isLoading canGoBack=$canGoBack canGoForward=$canGoForward"
                )
                if (!isLoading) {
                    if (::swipeRefresh.isInitialized) swipeRefresh.isRefreshing = false
                    val pageUrl = instance.getUrl().orEmpty().ifBlank { lastRequestedUrl }
                    if (pageUrl.startsWith("https://") || pageUrl.startsWith("http://")) {
                        lastSuccessfulUrl = pageUrl
                        preferences.edit().putString(KEY_LAST_URL, pageUrl).apply()
                        if (rendererRecoveryPending) {
                            rendererRecoveryPending = false
                            Log.i(TAG, "CHROMIUM_RECOVERY_COMPLETED url=$pageUrl")
                        }
                        scheduleRendererCrashTest(instance, pageUrl)
                    }
                    browserSlot.postDelayed({
                        captureAndVerifyPage(instance, pageUrl)
                    }, 1000L)
                }
            }
        })

        instance.setOnRenderProcessTerminatedListener { status, errorCode ->
            runOnUiThread {
                handleRendererTermination(instance, status, errorCode)
            }
        }

        instance.setDownloadHandler(object : CefriumBrowser.CefriumDownloadHandler {
            override fun onBeforeDownload(
                item: CefriumBrowser.DownloadItem,
                callback: CefriumBrowser.BeforeDownloadCallback
            ) {
                val name = item.getSuggestedName().orEmpty().ifBlank { "Download" }
                activeDownloadItems[name] = item
                runOnUiThread {
                    updateDownloadRecord(
                        name = name,
                        path = item.getFullPath().orEmpty(),
                        percent = item.getPercentComplete().coerceIn(0, 100),
                        complete = item.isComplete(),
                        canceled = item.isCanceled()
                    )
                }
                callback.allow(null)
            }

            override fun onDownloadUpdated(item: CefriumBrowser.DownloadItem) {
                val name = item.getSuggestedName().orEmpty().ifBlank { "Download" }
                runOnUiThread {
                    activeDownloadItems[name] = item
                    updateDownloadRecord(
                        name = name,
                        path = item.getFullPath().orEmpty(),
                        percent = item.getPercentComplete().coerceIn(0, 100),
                        complete = item.isComplete(),
                        canceled = item.isCanceled()
                    )
                }
            }
        })

        instance.setOnDownloadListener(object : CefriumBrowser.OnDownloadListener {
            override fun onDownloadUpdated(
                name: String,
                path: String,
                percent: Int,
                complete: Boolean,
                canceled: Boolean
            ) {
                runOnUiThread {
                    updateDownloadRecord(name, path, percent, complete, canceled)
                }
            }
        })

        if (savedState != null && initialUrl == lastSuccessfulUrl) {
            instance.restoreState(savedState)
        } else {
            instance.loadUrl(initialUrl)
        }
        Log.i(
            TAG,
            "CEF_BROWSER_CREATED url=$initialUrl activeRenderers=" +
                CefriumBrowser.activeRenderProcessCount()
        )
    }

    private fun handleRendererTermination(
        failedBrowser: CefriumBrowser,
        status: Int,
        errorCode: Int
    ) {
        if (isFinishing || isDestroyed || showingDownloads ||
            !::browser.isInitialized || browser !== failedBrowser
        ) return

        Log.e(
            TAG,
            "CHROMIUM_RENDERER_TERMINATED status=$status errorCode=$errorCode " +
                "url=$lastRequestedUrl activeRenderers=" +
                CefriumBrowser.activeRenderProcessCount()
        )

        if (rendererRecoveryAttempts >= 1) {
            rendererErrorPanel.visibility = View.VISIBLE
            return
        }

        rendererRecoveryAttempts++
        rendererRecoveryPending = true
        val restoreUrl = lastSuccessfulUrl
            .takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?: "about:blank"

        runCatching { failedBrowser.close() }
            .onFailure { Log.w(TAG, "Closing the terminated Chromium browser reported an error", it) }
        browserSlot.removeAllViews()
        rendererErrorPanel.visibility = View.GONE

        Log.i(TAG, "CHROMIUM_RECOVERY_STARTED restoreUrl=$restoreUrl")
        attachBrowser(restoreUrl)
    }

    private fun captureAndVerifyPage(instance: CefriumBrowser, url: String) {
        if (isFinishing || isDestroyed || showingDownloads ||
            !::browser.isInitialized || browser !== instance
        ) return

        instance.captureBitmap { bitmap ->
            if (!::browser.isInitialized || browser !== instance) {
                bitmap?.recycle()
                return@captureBitmap
            }
            if (bitmap == null) {
                Log.w(TAG, "PAGE_BITMAP_CAPTURE_FAILED url=$url")
                return@captureBitmap
            }

            val stepX = maxOf(1, bitmap.width / 80)
            val stepY = maxOf(1, bitmap.height / 80)
            var sampled = 0
            var visible = 0
            for (y in 0 until bitmap.height step stepY) {
                for (x in 0 until bitmap.width step stepX) {
                    val pixel = bitmap.getPixel(x, y)
                    sampled++
                    if (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel) > 70) {
                        visible++
                    }
                }
            }
            val visibleRatio = if (sampled > 0) visible.toDouble() / sampled else 0.0
            val ratioText = String.format(Locale.ROOT, "%.4f", visibleRatio)
            Log.i(
                TAG,
                "PAGE_BITMAP_CAPTURED url=$url width=" + bitmap.width +
                    " height=" + bitmap.height + " visibleRatio=$ratioText"
            )
            bitmap.recycle()

            // captureBitmap may omit Surface-backed compositor pixels on some Android
            // releases. Use load-state and device screenshots for page verification.
        }
    }

    private fun scheduleRendererCrashTest(instance: CefriumBrowser, url: String) {
        if (!testCrashRenderer || testCrashTriggered ||
            !url.startsWith("https://example.com")
        ) return

        testCrashTriggered = true
        Log.i(TAG, "TEST_RENDERER_CRASH_ARMED url=$url")
        browserSlot.postDelayed({
            if (!isFinishing && !isDestroyed &&
                ::browser.isInitialized && browser === instance
            ) {
                Log.i(TAG, "TEST_TRIGGER_RENDERER_CRASH url=$url")
                instance.loadUrl("chrome://crash")
            }
        }, 5000L)
    }

    private fun buildLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        setContentView(root)

        val content = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(content, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        browserSlot = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        swipeRefresh = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(Color.WHITE)
            setProgressBackgroundColorSchemeColor(Color.rgb(24, 24, 24))
            setOnChildScrollUpCallback { _, _ -> browserSlot.canScrollVertically(-1) }
            setOnRefreshListener {
                if (::browser.isInitialized && !showingDownloads) {
                    val currentUrl = browser.getUrl().orEmpty().ifBlank { lastSuccessfulUrl }
                    browser.loadUrl(currentUrl)
                } else {
                    isRefreshing = false
                }
            }
            addView(browserSlot, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }
        content.addView(swipeRefresh, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        downloadsList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(24))
            setBackgroundColor(Color.BLACK)
        }
        downloadsPanel = ScrollView(this).apply {
            visibility = View.GONE
            isFillViewport = true
            setBackgroundColor(Color.BLACK)
            addView(downloadsList)
        }
        content.addView(downloadsPanel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        rendererErrorPanel = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
            val message = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(28), dp(24), dp(28), dp(24))
                addView(textView("Page stopped unexpectedly", 18f, Color.WHITE, bold = true))
                addView(textView(
                    "Chromium stopped rendering this page. Reload to continue.",
                    13f,
                    Color.rgb(170, 170, 170)
                ).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(9), 0, dp(20))
                })
                addView(textView("RELOAD PAGE", 12f, Color.WHITE, bold = true).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(20), dp(12), dp(20), dp(12))
                    background = roundedBackground(Color.rgb(38, 38, 38), dp(12))
                    setOnClickListener { retryBrowserPage() }
                })
            }
            addView(message, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ))
        }
        content.addView(rendererErrorPanel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        root.addView(buildBottomBar())
    }

    private fun buildBottomBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(10))
            setBackgroundColor(Color.BLACK)
        }

        val searchShell = FrameLayout(this).apply {
            background = roundedBackground(Color.rgb(20, 20, 20), dp(18))
        }
        searchField = EditText(this).apply {
            hint = "Search or enter address"
            setHintTextColor(Color.rgb(160, 160, 160))
            setTextColor(Color.WHITE)
            textSize = 14f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            background = null
            setPadding(dp(15), 0, dp(12), 0)
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
            compoundDrawablePadding = dp(10)
            setSelectAllOnFocus(true)
        }
        searchField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE
            ) {
                navigateFromAddressBar()
                true
            } else false
        }
        searchField.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && showingDownloads) showBrowser()
        }
        searchShell.addView(searchField, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        bar.addView(searchShell, LinearLayout.LayoutParams(0, dp(54), 1f))

        shieldButton = createIconButton(R.drawable.ic_shield, "Toggle ad blocker") {
            toggleAdBlocking()
        }
        bar.addView(shieldButton, LinearLayout.LayoutParams(dp(48), dp(54)).apply {
            marginStart = dp(8)
        })

        val downloadsButton = createIconButton(
            R.drawable.ic_download_arrow,
            "Open downloads",
            transparentBackground = true
        ) {
            toggleDownloads()
        }
        bar.addView(downloadsButton, LinearLayout.LayoutParams(dp(48), dp(54)).apply {
            marginStart = dp(8)
        })
        return bar
    }

    private fun createIconButton(
        icon: Int,
        description: String,
        transparentBackground: Boolean = false,
        action: () -> Unit
    ): ImageButton {
        return ImageButton(this).apply {
            setImageResource(icon)
            contentDescription = description
            scaleType = android.widget.ImageView.ScaleType.CENTER
            background = if (transparentBackground) {
                android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            } else {
                roundedBackground(Color.rgb(17, 17, 17), dp(15))
            }
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            setOnClickListener { action() }
        }
    }

    private fun navigateFromAddressBar() {
        val target = BrowserAddressResolver.resolve(searchField.text?.toString().orEmpty())
        if (target.isBlank()) return

        showBrowser()
        rendererRecoveryAttempts = 0
        rendererRecoveryPending = false
        lastRequestedUrl = target
        rendererErrorPanel.visibility = View.GONE
        browser.loadUrl(target)
        searchField.setText(target)
        searchField.clearFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(searchField.windowToken, 0)
    }

    private fun retryBrowserPage() {
        rendererRecoveryAttempts = 0
        rendererRecoveryPending = false
        rendererErrorPanel.visibility = View.GONE
        val oldBrowser = browser
        val restoreUrl = lastSuccessfulUrl
        runCatching { oldBrowser.close() }
            .onFailure { Log.w(TAG, "Closing browser before manual retry reported an error", it) }
        browserSlot.removeAllViews()
        attachBrowser(restoreUrl)
    }

    private fun toggleAdBlocking() {
        adBlockingEnabled = !adBlockingEnabled
        preferences.edit().putBoolean(KEY_AD_BLOCK, adBlockingEnabled).apply()
        browser.setAdBlockEnabled(adBlockingEnabled)
        updateShieldButton()
    }

    private fun updateShieldButton() {
        if (!::shieldButton.isInitialized) return
        shieldButton.alpha = if (adBlockingEnabled) 1f else 0.45f
        shieldButton.contentDescription = if (adBlockingEnabled) "Ad blocker on" else "Ad blocker off"
    }

    private fun toggleDownloads() {
        if (showingDownloads) showBrowser() else {
            showingDownloads = true
            swipeRefresh.visibility = View.GONE
            downloadsPanel.visibility = View.VISIBLE
            searchField.clearFocus()
            renderDownloads()
        }
    }

    private fun showBrowser() {
        showingDownloads = false
        downloadsPanel.visibility = View.GONE
        swipeRefresh.visibility = View.VISIBLE
    }

    private fun updateDownloadRecord(
        name: String,
        path: String,
        percent: Int,
        complete: Boolean,
        canceled: Boolean
    ) {
        val item = activeDownloadItems[name]
        val id = item?.getId() ?: (path.ifBlank { name }).hashCode()
        val record = downloadRecords[id] ?: DownloadRecord(
            id = id,
            name = name.ifBlank { "Download" },
            url = item?.getUrl().orEmpty(),
            path = path,
            totalBytes = 0L,
            receivedBytes = 0L,
            speedBytesPerSecond = 0L,
            percent = 0,
            complete = false,
            paused = false,
            canceled = false,
            interrupted = false,
            updatedAt = System.currentTimeMillis()
        )

        record.name = name.ifBlank { record.name }
        record.url = item?.getUrl().orEmpty().ifBlank { record.url }
        record.path = path.ifBlank { item?.getFullPath().orEmpty().ifBlank { record.path } }
        record.totalBytes = item?.getTotalBytes() ?: record.totalBytes
        record.receivedBytes = item?.getReceivedBytes() ?: record.receivedBytes
        record.speedBytesPerSecond = item?.getCurrentSpeed() ?: 0L
        record.percent = (item?.getPercentComplete() ?: percent).coerceIn(0, 100)
        record.complete = complete || (item?.isComplete() == true)
        record.paused = item?.isPaused() == true
        record.canceled = canceled || (item?.isCanceled() == true)
        record.interrupted = item?.isInterrupted() == true
        record.updatedAt = System.currentTimeMillis()
        record.liveItem = if (record.complete || record.canceled || record.interrupted) null else item

        downloadRecords[id] = record
        if (record.complete || record.canceled || record.interrupted) {
            activeDownloadItems.remove(name)
        }
        publishDownloadNotification(record)

        persistDownloads()
        if (showingDownloads) renderDownloads()
    }

    private fun createDownloadNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                DOWNLOAD_CHANNEL_ID,
                "Browser downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Download progress and completion" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun publishDownloadNotification(record: DownloadRecord) {
        if (record.canceled || record.interrupted) {
            NotificationManagerCompat.from(this).cancel(record.id)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            if (!notificationPermissionRequested) {
                notificationPermissionRequested = true
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            }
            return
        }

        val remaining = (record.totalBytes - record.receivedBytes).coerceAtLeast(0L)
        val eta = if (!record.complete && record.speedBytesPerSecond > 0L && record.totalBytes > 0L) {
            " · ${formatDuration(remaining / record.speedBytesPerSecond)} left"
        } else ""
        val status = if (record.complete) {
            "Download complete · ${formatBytes(record.receivedBytes)}"
        } else if (record.totalBytes > 0L) {
            "${record.percent}% · ${formatBytes(record.receivedBytes)} of ${formatBytes(record.totalBytes)}$eta"
        } else {
            "Download started · ${formatBytes(record.receivedBytes)} received"
        }
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, record.id, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(record.name)
            .setContentText(status)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(!record.complete)
            .setAutoCancel(record.complete)
            .setProgress(100, record.percent.coerceIn(0, 100), !record.complete && record.totalBytes <= 0L)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(record.id, notification) }
            .onFailure { Log.w(TAG, "Unable to post download notification", it) }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS &&
            grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            downloadRecords.values.filter { !it.complete && !it.canceled && !it.interrupted }
                .forEach(::publishDownloadNotification)
        }
    }

    private fun formatDuration(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0L)
        val hours = safe / 3600L
        val minutes = (safe % 3600L) / 60L
        val secs = safe % 60L
        return when {
            hours > 0L -> "${hours}h ${minutes}m"
            minutes > 0L -> "${minutes}m ${secs}s"
            else -> "${secs}s"
        }
    }

    private fun renderDownloads() {
        if (!::downloadsList.isInitialized) return
        downloadsList.removeAllViews()
        downloadsList.addView(textView("Downloads", 24f, Color.WHITE, bold = true))
        downloadsList.addView(textView(
            "Files downloaded in this browser",
            13f,
            Color.rgb(155, 155, 155)
        ).apply { setPadding(0, dp(7), 0, dp(25)) })

        if (downloadRecords.isEmpty()) {
            downloadsList.addView(textView(
                "No downloads yet", 14f, Color.rgb(180, 180, 180)
            ).apply { setPadding(0, dp(24), 0, 0) })
            return
        }

        downloadRecords.values.sortedByDescending { it.updatedAt }
            .forEach { downloadsList.addView(createDownloadCard(it)) }
    }

    private fun createDownloadCard(record: DownloadRecord): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(12))
            background = roundedBackground(Color.rgb(16, 16, 16), dp(14))
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(textView(
            record.name, 14f, Color.WHITE, bold = true
        ).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }, LinearLayout.LayoutParams(0, -2, 1f))

        val statusText = when {
            record.complete -> "DONE"
            record.canceled -> "CANCELED"
            record.interrupted -> "INTERRUPTED"
            record.paused -> "PAUSED"
            else -> "${record.percent}%"
        }
        titleRow.addView(textView(statusText, 10f, Color.rgb(190, 190, 190), bold = true))
        card.addView(titleRow)

        val detail = when {
            record.complete -> "Completed · ${formatBytes(record.receivedBytes)}"
            record.canceled -> "Canceled"
            record.interrupted -> "Download interrupted"
            record.paused -> "Paused · ${formatBytes(record.receivedBytes)} received"
            else -> "${formatSpeed(record.speedBytesPerSecond)} · ${formatBytes(record.receivedBytes)} of ${formatBytes(record.totalBytes)}"
        }
        card.addView(textView(detail, 12f, Color.rgb(165, 165, 165)).apply {
            setPadding(0, dp(8), 0, dp(10))
        })

        if (!record.complete && !record.canceled && !record.interrupted) {
            val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                this.progress = record.percent
                progressTintList = ColorStateList.valueOf(Color.WHITE)
                progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(58, 58, 58))
            }
            card.addView(progress, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(4)
            ))

            val liveItem = record.liveItem
            if (liveItem != null) {
                val actions = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                    setPadding(0, dp(9), 0, 0)
                }
                val pauseResume = textView(
                    if (record.paused) "RESUME" else "PAUSE",
                    11f, Color.WHITE, bold = true
                ).apply {
                    setPadding(dp(12), dp(7), dp(12), dp(7))
                    background = roundedBackground(Color.rgb(40, 40, 40), dp(9))
                    setOnClickListener {
                        if (record.paused) liveItem.resume() else liveItem.pause()
                    }
                }
                val cancel = textView("CANCEL", 11f, Color.WHITE, bold = true).apply {
                    setPadding(dp(12), dp(7), dp(12), dp(7))
                    background = roundedBackground(Color.rgb(40, 40, 40), dp(9))
                    setOnClickListener { liveItem.cancel() }
                }
                actions.addView(pauseResume)
                actions.addView(cancel, LinearLayout.LayoutParams(-2, -2).apply {
                    marginStart = dp(8)
                })
                card.addView(actions)
            }
        } else if (record.complete) {
            card.setOnClickListener { openDownloadedFile(record) }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(10))
            addView(card)
        }
    }

    private fun openDownloadedFile(record: DownloadRecord) {
        val file = record.path.takeIf { it.isNotBlank() }?.let(::File)
        if (file == null || !file.exists()) {
            Toast.makeText(this, "Downloaded file is not available at its saved path", Toast.LENGTH_LONG).show()
            return
        }

        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            val extension = file.extension.lowercase(Locale.ROOT)
            val mime = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(extension) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        }.onFailure {
            Toast.makeText(this, "No installed app can open this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun persistDownloads() {
        val array = JSONArray()
        downloadRecords.values.forEach { record ->
            array.put(JSONObject().apply {
                put("id", record.id)
                put("name", record.name)
                put("url", record.url)
                put("path", record.path)
                put("total", record.totalBytes)
                put("received", record.receivedBytes)
                put("speed", record.speedBytesPerSecond)
                put("percent", record.percent)
                put("complete", record.complete)
                put("paused", record.paused)
                put("canceled", record.canceled)
                put("interrupted", record.interrupted)
                put("updated", record.updatedAt)
            })
        }
        preferences.edit().putString(KEY_DOWNLOADS, array.toString()).apply()
    }

    private fun restoreSavedDownloads() {
        val raw = preferences.getString(KEY_DOWNLOADS, null) ?: return
        runCatching {
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val complete = item.optBoolean("complete")
                val record = DownloadRecord(
                    id = item.optInt("id"),
                    name = item.optString("name", "Download"),
                    url = item.optString("url"),
                    path = item.optString("path"),
                    totalBytes = item.optLong("total"),
                    receivedBytes = item.optLong("received"),
                    speedBytesPerSecond = 0L,
                    percent = item.optInt("percent").coerceIn(0, 100),
                    complete = complete,
                    paused = false,
                    canceled = item.optBoolean("canceled"),
                    interrupted = item.optBoolean("interrupted") || !complete,
                    updatedAt = item.optLong("updated")
                )
                downloadRecords[record.id] = record
            }
        }
    }

    private fun textView(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean = false
    ): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun roundedBackground(fill: Int, radius: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius.toFloat()
    }

    private fun formatBytes(value: Long): String {
        if (value <= 0L) return "—"
        val kb = value / 1024.0
        if (kb < 1024) return String.format(Locale.ROOT, "%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.ROOT, "%.1f MB", mb)
        return String.format(Locale.ROOT, "%.2f GB", mb / 1024.0)
    }

    private fun formatSpeed(value: Long): String {
        if (value <= 0L) return "Starting…"
        if (value < 1024) return "$value B/s"
        if (value < 1024L * 1024L) return String.format(Locale.ROOT, "%.0f KB/s", value / 1024.0)
        return String.format(Locale.ROOT, "%.1f MB/s", value / (1024.0 * 1024.0))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    @Deprecated("Use the activity back dispatcher")
    override fun onBackPressed() {
        when {
            showingDownloads -> showBrowser()
            ::browser.isInitialized && browser.canGoBack() -> browser.goBack()
            else -> super.onBackPressed()
        }
    }

    override fun onPause() {
        if (::browser.isInitialized) browser.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::browser.isInitialized) browser.onResume()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::browser.isInitialized) outState.putBundle(KEY_BROWSER_STATE, browser.saveState())
        preferences.edit().putString(KEY_LAST_URL, lastSuccessfulUrl).apply()
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (::browser.isInitialized) browser.close()
        super.onDestroy()
    }

    private companion object {
        const val PREFERENCES = "browser_preferences"
        const val HOME_URL = "https://www.google.com/"
        const val DOWNLOAD_CHANNEL_ID = "browser_downloads"
        const val REQUEST_NOTIFICATIONS = 4102
        const val KEY_AD_BLOCK = "ad_block_enabled"
        const val KEY_DOWNLOADS = "downloads_json"
        const val KEY_BROWSER_STATE = "browser_state"
        const val KEY_LAST_URL = "last_successful_url"
        const val EXTRA_TEST_URL = "com.search.browser.TEST_URL"
        const val EXTRA_TEST_CRASH_RENDERER = "com.search.browser.TEST_CRASH_RENDERER"
        const val TAG = "Browser"
    }
}
