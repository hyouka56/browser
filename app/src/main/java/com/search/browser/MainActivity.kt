package com.search.browser

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.app.role.RoleManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
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
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
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
    private lateinit var defaultBrowserButton: TextView

    private var rendererRecoveryAttempts = 0
    private var rendererRecoveryPending = false
    private var lastRequestedUrl = "about:blank"
    private var lastSuccessfulUrl = HOME_URL
    private var testCrashRenderer = false
    private var testCrashTriggered = false
    private val preferences by lazy { getSharedPreferences(PREFERENCES, MODE_PRIVATE) }
    private val downloadRecords = LinkedHashMap<Int, DownloadRecord>()
    private val activeDownloadItems = LinkedHashMap<String, CefriumBrowser.DownloadItem>()
    private val pendingPublicSaveIds = mutableSetOf<Int>()
    private var adBlockingEnabled = true
    private var showingDownloads = false

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
        NotificationManagerCompat.from(this).cancel(DOWNLOAD_NOTIFICATION_ID)
        buildLayout()

        testCrashRenderer = BuildConfig.DEBUG &&
            intent.getBooleanExtra(EXTRA_TEST_CRASH_RENDERER, false)
        val testUrl = if (BuildConfig.DEBUG) {
            intent.getStringExtra(EXTRA_TEST_URL)?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        val incomingUrl = intent?.dataString?.takeIf(::isSupportedWebUrl)
        val startUrl = testUrl ?: incomingUrl ?: lastSuccessfulUrl
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
        instance.addDocumentStartJavaScript(
            """
            (() => {
                const stylesheet = new CSSStyleSheet();
                stylesheet.replaceSync(`
                    :root, html, body {
                        background: #000000 !important;
                        color-scheme: dark !important;
                    }
                    body {
                        min-height: 100vh;
                    }
                `);
                document.adoptedStyleSheets = [...document.adoptedStyleSheets, stylesheet];
            })();
            """.trimIndent(),
            setOf("*")
        )
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
                    instance.setDarkMode(true)
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
                val suggestedName = item.getSuggestedName().orEmpty()
                val urlName = Uri.parse(item.getUrl().orEmpty()).lastPathSegment.orEmpty()
                val mimeType = item.getMimeType().orEmpty()
                val isApk = suggestedName.endsWith(".apk", true) ||
                    urlName.substringBefore("?").endsWith(".apk", true) ||
                    mimeType.equals(APK_MIME_TYPE, ignoreCase = true)
                val isZip = suggestedName.endsWith(".zip", true) ||
                    urlName.substringBefore("?").endsWith(".zip", true) ||
                    mimeType.contains("zip", ignoreCase = true)
                if (!isApk && !isZip) {
                    callback.cancel()
                    return
                }

                val alreadyDownloading = activeDownloadItems.values.any {
                    !it.isComplete() && !it.isCanceled() && !it.isInterrupted()
                }
                if (alreadyDownloading) {
                    callback.cancel()
                    return
                }

                val name = normalizedDownloadName(suggestedName.ifBlank { urlName }, isZip)
                val stagingDirectory = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: File(filesDir, "downloads")
                if (!stagingDirectory.exists() && !stagingDirectory.mkdirs()) {
                    callback.cancel()
                    return
                }

                activeDownloadItems.clear()
                downloadRecords.clear()
                val target = File(stagingDirectory, item.getId().toString() + "-" + name)
                target.delete()
                activeDownloadItems[name] = item
                callback.allow(target.absolutePath)
            }

            override fun onDownloadUpdated(item: CefriumBrowser.DownloadItem) {
                val suggestedName = item.getSuggestedName().orEmpty()
                val urlName = Uri.parse(item.getUrl().orEmpty()).lastPathSegment.orEmpty()
                val isZip = suggestedName.endsWith(".zip", true) ||
                    urlName.substringBefore("?").endsWith(".zip", true) ||
                    item.getMimeType().orEmpty().contains("zip", ignoreCase = true)
                val name = normalizedDownloadName(suggestedName.ifBlank { urlName }, isZip)
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

        defaultBrowserButton = textView("⋮", 26f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            contentDescription = "Browser options"
            visibility = if (isDefaultBrowser()) View.GONE else View.VISIBLE
            setOnClickListener { anchor ->
                if (isDefaultBrowser()) {
                    updateDefaultBrowserButton()
                } else {
                    PopupMenu(this@MainActivity, anchor).apply {
                        menu.add("Set as default browser")
                        setOnMenuItemClickListener {
                            requestDefaultBrowserRole()
                            true
                        }
                    }.show()
                }
            }
        }
        bar.addView(defaultBrowserButton, LinearLayout.LayoutParams(dp(40), dp(54)).apply {
            marginStart = dp(2)
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

    private fun isSupportedWebUrl(value: String?): Boolean {
        val scheme = value?.let { Uri.parse(it).scheme }?.lowercase(Locale.ROOT)
        return scheme == "http" || scheme == "https"
    }

    private fun isDefaultBrowser(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val roleManager = getSystemService(RoleManager::class.java) ?: return false
        return roleManager.isRoleAvailable(RoleManager.ROLE_BROWSER) &&
            roleManager.isRoleHeld(RoleManager.ROLE_BROWSER)
    }

    private fun updateDefaultBrowserButton() {
        if (!::defaultBrowserButton.isInitialized) return
        defaultBrowserButton.visibility = if (isDefaultBrowser()) View.GONE else View.VISIBLE
    }

    private fun requestDefaultBrowserRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_BROWSER)) {
                if (roleManager.isRoleHeld(RoleManager.ROLE_BROWSER)) {
                    Toast.makeText(this, "Browser is already the default", Toast.LENGTH_SHORT).show()
                } else {
                    startActivityForResult(
                        roleManager.createRequestRoleIntent(RoleManager.ROLE_BROWSER),
                        REQUEST_BROWSER_ROLE
                    )
                }
                return
            }
        }
        runCatching {
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }.onFailure {
            Toast.makeText(this, "Open Android default apps settings to choose Browser", Toast.LENGTH_LONG).show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val url = intent.dataString?.takeIf(::isSupportedWebUrl) ?: return
        showBrowser()
        rendererRecoveryAttempts = 0
        rendererRecoveryPending = false
        lastRequestedUrl = url
        browser.loadUrl(url)
        searchField.setText(url)
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
        val incomingPath = path.ifBlank { item?.getFullPath().orEmpty() }
        if (!record.path.startsWith("content://") && incomingPath.isNotBlank()) {
            record.path = incomingPath
        }
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
        if (record.complete && !record.path.startsWith("content://")) {
            persistDownloads()
            if (showingDownloads) renderDownloads()
            saveCompletedDownloadToPublicDownloads(record)
            return
        }

        publishDownloadNotification(record)
        persistDownloads()
        if (showingDownloads) renderDownloads()
    }

    private fun normalizedApkName(value: String): String {
        val cleaned = value.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .ifBlank { "download.apk" }
        return if (cleaned.endsWith(".apk", true)) cleaned else "$cleaned.apk"
    }

    private fun normalizedDownloadName(value: String, isZip: Boolean): String {
        val fallback = if (isZip) "download.zip" else "download.apk"
        val cleaned = value.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .ifBlank { fallback }
        return when {
            cleaned.endsWith(".apk", true) || cleaned.endsWith(".zip", true) -> cleaned
            isZip -> "$cleaned.zip"
            else -> "$cleaned.apk"
        }
    }

    private fun saveCompletedDownloadToPublicDownloads(record: DownloadRecord) {
        if (!pendingPublicSaveIds.add(record.id)) return
        val downloadedFile = record.path.takeIf { it.isNotBlank() }?.let(::File)
        if (downloadedFile == null || !downloadedFile.isFile) {
            pendingPublicSaveIds.remove(record.id)
            record.interrupted = true
            record.complete = false
            persistDownloads()
            if (showingDownloads) renderDownloads()
            return
        }

        Thread({
            var destinationUri: Uri? = null
            var extractedApk: File? = null
            var extractionDirectory: File? = null
            var failure: Throwable? = null
            var displayName = normalizedApkName(record.name)
            try {
                val apkSource = if (downloadedFile.extension.equals("zip", true) ||
                    record.name.endsWith(".zip", true)
                ) {
                    extractionDirectory = File(cacheDir, "apk-download-${record.id}").apply {
                        deleteRecursively()
                        if (!mkdirs() && !isDirectory) {
                            throw IllegalStateException("Unable to prepare the APK extraction folder")
                        }
                    }
                    ApkDownloadArchive.extractSingleApk(downloadedFile, extractionDirectory!!).also {
                        extractedApk = it
                    }
                } else {
                    downloadedFile
                }
                displayName = normalizedApkName(apkSource.name)

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, APK_MIME_TYPE)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                destinationUri = contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    values
                ) ?: throw IllegalStateException("Unable to create a Downloads entry")

                val output = contentResolver.openOutputStream(destinationUri!!, "w")
                    ?: throw IllegalStateException("Unable to open the Downloads file")
                output.use { stream ->
                    apkSource.inputStream().use { input -> input.copyTo(stream) }
                }

                val ready = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                contentResolver.update(destinationUri!!, ready, null, null)
                if (downloadedFile.exists() && !downloadedFile.delete()) {
                    Log.w(TAG, "Temporary download could not be deleted: " + downloadedFile.absolutePath)
                }
                extractedApk?.let { if (it.exists()) it.delete() }
            } catch (error: Throwable) {
                failure = error
                destinationUri?.let { runCatching { contentResolver.delete(it, null, null) } }
                Log.e(TAG, "Unable to save the downloaded APK", error)
            } finally {
                extractionDirectory?.deleteRecursively()
            }

            val savedUri = destinationUri.takeIf { failure == null }
            runOnUiThread {
                pendingPublicSaveIds.remove(record.id)
                if (savedUri != null) {
                    record.name = displayName
                    record.path = savedUri.toString()
                    record.complete = true
                    record.interrupted = false
                    record.percent = 100
                } else {
                    record.complete = false
                    record.interrupted = true
                }
                record.updatedAt = System.currentTimeMillis()
                publishDownloadNotification(record)
                persistDownloads()
                if (showingDownloads) renderDownloads()
            }
        }, "public-apk-download-copy").start()
    }

    private fun createApkInstallIntent(uri: Uri): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            return Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName")
            )
        }
        return Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun publishDownloadNotification(record: DownloadRecord) {
        // Downloads stay visible in the in-app Downloads panel; suppress Android pop-up notifications.
        NotificationManagerCompat.from(this).cancel(DOWNLOAD_NOTIFICATION_ID)
    }

    private fun renderDownloads() {
        if (!::downloadsList.isInitialized) return
        downloadsList.removeAllViews()
        downloadsList.addView(textView("Downloads", 24f, Color.WHITE, bold = true))
        downloadsList.addView(textView(
            "APK files saved to Downloads",
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
            .take(1)
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
        if (!record.name.endsWith(".apk", true)) {
            Toast.makeText(this, "Only APK files can be installed here", Toast.LENGTH_SHORT).show()
            return
        }

        runCatching {
            val uri = if (record.path.startsWith("content://")) {
                Uri.parse(record.path)
            } else {
                val file = record.path.takeIf { it.isNotBlank() }?.let(::File)
                    ?: throw IllegalStateException("APK file path is missing")
                if (!file.exists()) throw IllegalStateException("APK file is missing")
                FileProvider.getUriForFile(this, "$packageName.files", file)
            }
            val needsInstallPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !packageManager.canRequestPackageInstalls()
            startActivity(createApkInstallIntent(uri))
            if (needsInstallPermission) {
                Toast.makeText(
                    this,
                    "Allow installs from Browser, then tap the APK notification again",
                    Toast.LENGTH_LONG
                ).show()
            }
        }.onFailure {
            Toast.makeText(this, "Unable to open the APK installer", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "Unable to open APK installer", it)
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
        updateDefaultBrowserButton()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.canRequestPackageInstalls()
        ) {
            downloadRecords.values
                .filter { it.complete && it.path.startsWith("content://") }
                .forEach(::publishDownloadNotification)
        }
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
        const val DOWNLOAD_NOTIFICATION_ID = 4103
        const val REQUEST_BROWSER_ROLE = 4104
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        const val KEY_AD_BLOCK = "ad_block_enabled"
        const val KEY_DOWNLOADS = "downloads_json"
        const val KEY_BROWSER_STATE = "browser_state"
        const val KEY_LAST_URL = "last_successful_url"
        const val EXTRA_TEST_URL = "com.search.browser.TEST_URL"
        const val EXTRA_TEST_CRASH_RENDERER = "com.search.browser.TEST_CRASH_RENDERER"
        const val TAG = "Browser"
    }
}
