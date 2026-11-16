package com.search.browser

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import android.content.pm.PackageManager
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import com.cefrium.CefriumBrowser
import com.cefrium.CefriumDownloadClient
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
    private val activeDownloadItems = LinkedHashMap<Int, CefriumBrowser.DownloadItem>()
    private val pendingPublicSaveIds = mutableSetOf<Int>()
    private var adBlockingEnabled = true
    private var showingDownloads = false
    private var notificationPermissionRequested = false
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationPermissionRequested = false
        if (granted) {
            downloadRecords.values.maxByOrNull { it.updatedAt }
                ?.takeIf { it.path.startsWith("content://") || it.canceled || it.interrupted }
                ?.let(::publishDownloadNotification)
        }
    }

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
        val incomingUrl = intent?.dataString?.takeIf(::isSupportedWebUrl)
        val startUrl = testUrl ?: incomingUrl ?: lastSuccessfulUrl
        attachBrowser(startUrl, savedInstanceState?.getBundle(KEY_BROWSER_STATE))
        if (intent.getBooleanExtra(EXTRA_SHOW_DOWNLOADS, false)) showDownloadsPanel()

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
        if (BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_TEST_DOWNLOAD_PAGE, false)) {
            instance.addDocumentStartJavaScript(
                """
                (() => {
                    const installDownloadTest = () => {
                        if (!document.body) return;
                        document.title = "Download Test";
                        document.body.replaceChildren();
                        Object.assign(document.body.style, {
                            margin: "0",
                            padding: "0",
                            background: "#000",
                            color: "#fff",
                            overflow: "hidden"
                        });

                        const addDownloadButton = (id, label, fileName, mimeType, content, top) => {
                            const button = document.createElement("button");
                            button.id = id;
                            button.textContent = label;
                            button.style.cssText = `position:fixed;left:0;width:100vw;top:${'$'}{top};height:45vh;font-size:26px;background:#181818;color:#fff;border:2px solid #fff;z-index:2147483647;`;
                            button.addEventListener("click", () => {
                                const blob = new Blob([content], { type: mimeType });
                                const url = URL.createObjectURL(blob);
                                const link = document.createElement("a");
                                link.href = url;
                                link.download = fileName;
                                document.body.appendChild(link);
                                link.click();
                                link.remove();
                                setTimeout(() => URL.revokeObjectURL(url), 60000);
                            });
                            document.body.appendChild(button);
                        };

                        addDownloadButton(
                            "download-file-test",
                            "Download test file",
                            "download-test.txt",
                            "text/plain",
                            "Browser download integration test\n",
                            "0"
                        );
                        addDownloadButton(
                            "download-apk-test",
                            "Download APK test file",
                            "download-test.apk",
                            "application/vnd.android.package-archive",
                            "APK download notification fixture",
                            "50vh"
                        );
                    };

                    if (document.readyState === "loading") {
                        document.addEventListener("DOMContentLoaded", installDownloadTest, { once: true });
                    } else {
                        installDownloadTest();
                    }
                })();
                """.trimIndent(),
                setOf("*")
            )
        }
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

        instance.setDownloadClient(object : CefriumDownloadClient {
            override fun onBeforeDownload(
                browser: CefriumBrowser,
                item: CefriumBrowser.DownloadItem,
                callback: CefriumBrowser.BeforeDownloadCallback
            ) {
                val suggestedName = item.getSuggestedName().orEmpty()
                val urlName = Uri.parse(item.getUrl().orEmpty()).lastPathSegment.orEmpty()
                val mimeType = item.getMimeType().orEmpty()
                val name = DownloadFileSupport.normalizedName(suggestedName, urlName, mimeType)
                val downloadId = item.getId()

                val alreadyDownloading = activeDownloadItems.values.any {
                    !it.isComplete() && !it.isCanceled() && !it.isInterrupted()
                }
                if (alreadyDownloading) {
                    Log.w(TAG, "DOWNLOAD_REQUEST_REJECTED id=$downloadId name=$name reason=another_download_active")
                    callback.cancel()
                    return
                }
                val stagingDirectory = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: File(filesDir, "downloads")
                if (!stagingDirectory.exists() && !stagingDirectory.mkdirs()) {
                    Log.e(TAG, "DOWNLOAD_REQUEST_REJECTED id=$downloadId name=$name reason=staging_directory_unavailable")
                    callback.cancel()
                    return
                }

                val target = File(stagingDirectory, "$downloadId-$name")
                target.delete()
                activeDownloadItems[downloadId] = item
                Log.i(TAG, "DOWNLOAD_REQUEST_ACCEPTED id=$downloadId name=$name mimeType=${DownloadFileSupport.mimeTypeFor(name, mimeType)} target=${target.absolutePath}")
                callback.allow(target.absolutePath)
            }

            override fun onDownloadUpdated(
                browser: CefriumBrowser,
                item: CefriumBrowser.DownloadItem
            ) {
                val path = item.getFullPath().orEmpty()
                val pathName = path.takeIf { it.isNotBlank() }?.let { File(it).name }
                val suggestedName = pathName ?: item.getSuggestedName().orEmpty()
                val urlName = Uri.parse(item.getUrl().orEmpty()).lastPathSegment.orEmpty()
                val mimeType = item.getMimeType().orEmpty()
                val name = DownloadFileSupport.normalizedName(suggestedName, urlName, mimeType)
                val downloadId = item.getId()
                runOnUiThread {
                    if (!item.isComplete() && !item.isCanceled() && !item.isInterrupted()) {
                        activeDownloadItems[downloadId] = item
                    }
                    updateDownloadRecord(
                        downloadId = downloadId,
                        name = name,
                        path = path,
                        percent = item.getPercentComplete().coerceIn(0, 100),
                        complete = item.isComplete(),
                        canceled = item.isCanceled(),
                        mimeType = mimeType
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
        if (intent.getBooleanExtra(EXTRA_SHOW_DOWNLOADS, false)) {
            showDownloadsPanel()
            return
        }
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
        if (showingDownloads) showBrowser() else showDownloadsPanel()
    }

    private fun showDownloadsPanel() {
        showingDownloads = true
        swipeRefresh.visibility = View.GONE
        downloadsPanel.visibility = View.VISIBLE
        searchField.clearFocus()
        renderDownloads()
    }

    private fun showBrowser() {
        showingDownloads = false
        downloadsPanel.visibility = View.GONE
        swipeRefresh.visibility = View.VISIBLE
    }

    private fun updateDownloadRecord(
        downloadId: Int,
        name: String,
        path: String,
        percent: Int,
        complete: Boolean,
        canceled: Boolean,
        mimeType: String
    ) {
        requestDownloadNotificationPermissionIfNeeded()
        val item = activeDownloadItems[downloadId]
        val id = downloadId
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
        record.mimeType = DownloadFileSupport.mimeTypeFor(
            record.name,
            item?.getMimeType().orEmpty().ifBlank { mimeType }
        )
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
            activeDownloadItems.remove(downloadId)
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

    private fun saveCompletedDownloadToPublicDownloads(record: DownloadRecord) {
        if (!pendingPublicSaveIds.add(record.id)) return
        val downloadedFile = record.path.takeIf { it.isNotBlank() }?.let(::File)
        if (downloadedFile == null || !downloadedFile.isFile) {
            pendingPublicSaveIds.remove(record.id)
            record.interrupted = true
            record.complete = false
            record.updatedAt = System.currentTimeMillis()
            publishDownloadNotification(record)
            persistDownloads()
            if (showingDownloads) renderDownloads()
            Log.e(TAG, "DOWNLOAD_FILE_SAVE_FAILED name=${record.name} reason=missing_source")
            return
        }

        Thread({
            var destinationUri: Uri? = null
            var failure: Throwable? = null
            var extractedApk: File? = null
            var extractionDirectory: File? = null
            var sourceFile = downloadedFile
            if (downloadedFile.extension.equals("zip", true)) {
                val directory = File(cacheDir, "apk-download-${record.id}")
                val extraction = runCatching {
                    ApkDownloadArchive.extractSingleApk(downloadedFile, directory)
                }
                if (extraction.isSuccess) {
                    val extractedFile = extraction.getOrThrow()
                    extractedApk = extractedFile
                    extractionDirectory = directory
                    sourceFile = extractedFile
                } else {
                    directory.deleteRecursively()
                    Log.i(TAG, "Downloaded ZIP is a regular file, not a single-APK archive: ${record.name}")
                }
            }
            val displayName = if (sourceFile == downloadedFile) record.name else sourceFile.name
            val mimeType = DownloadFileSupport.mimeTypeFor(
                displayName,
                if (sourceFile.extension.equals("apk", true)) APK_MIME_TYPE else record.mimeType
            )
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
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
                    sourceFile.inputStream().use { input -> input.copyTo(stream) }
                }

                val ready = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                val updated = contentResolver.update(destinationUri!!, ready, null, null)
                if (updated <= 0) throw IllegalStateException("Unable to publish the Downloads file")
                if (downloadedFile.exists() && !downloadedFile.delete()) {
                    Log.w(TAG, "Temporary download could not be deleted: " + downloadedFile.absolutePath)
                }
            } catch (error: Throwable) {
                failure = error
                destinationUri?.let { runCatching { contentResolver.delete(it, null, null) } }
                Log.e(TAG, "Unable to save the downloaded file", error)
            } finally {
                extractedApk?.let { if (it.exists()) it.delete() }
                extractionDirectory?.deleteRecursively()
            }

            val savedUri = destinationUri.takeIf { failure == null }
            runOnUiThread {
                pendingPublicSaveIds.remove(record.id)
                if (savedUri != null) {
                    record.name = displayName
                    record.path = savedUri.toString()
                    record.mimeType = mimeType
                    record.complete = true
                    record.interrupted = false
                    record.percent = 100
                    Log.i(TAG, "DOWNLOAD_FILE_SAVED name=${record.name} mimeType=$mimeType bytes=${record.receivedBytes} uri=$savedUri")
                } else {
                    record.complete = false
                    record.interrupted = true
                    Log.e(TAG, "DOWNLOAD_FILE_SAVE_FAILED name=${record.name}")
                }
                record.updatedAt = System.currentTimeMillis()
                publishDownloadNotification(record)
                persistDownloads()
                if (showingDownloads) renderDownloads()
            }
        }, "public-download-copy").start()
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

    private fun createDownloadNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            DOWNLOAD_NOTIFICATION_CHANNEL,
            "Downloads",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Download progress and completed files"
            setShowBadge(true)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun requestDownloadNotificationPermissionIfNeeded(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return true

        val alreadyAsked = preferences.getBoolean(KEY_NOTIFICATION_PERMISSION_ASKED, false)
        if (!alreadyAsked && !notificationPermissionRequested) {
            notificationPermissionRequested = true
            preferences.edit().putBoolean(KEY_NOTIFICATION_PERMISSION_ASKED, true).apply()
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        return false
    }

    private fun downloadNotificationPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_SHOW_DOWNLOADS, true)
        }
        return PendingIntent.getActivity(
            this,
            REQUEST_OPEN_DOWNLOADS,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun publishDownloadNotification(record: DownloadRecord) {
        if (!requestDownloadNotificationPermissionIfNeeded()) return
        val manager = NotificationManagerCompat.from(this)
        if (!manager.areNotificationsEnabled()) return

        val ongoing = !record.complete && !record.canceled && !record.interrupted
        val title = when {
            record.complete -> "Download complete"
            record.canceled -> "Download canceled"
            record.interrupted -> "Download failed"
            else -> "Downloading ${record.name}"
        }
        val detail = when {
            record.complete -> "${record.name} saved to Downloads"
            record.canceled -> record.name
            record.interrupted -> "${record.name} could not be saved"
            record.totalBytes > 0L -> "${record.percent}% · ${formatBytes(record.receivedBytes)} of ${formatBytes(record.totalBytes)}"
            record.receivedBytes > 0L -> "${formatBytes(record.receivedBytes)} downloaded"
            else -> "Preparing ${record.name}"
        }
        val builder = NotificationCompat.Builder(this, DOWNLOAD_NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_download_arrow)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(downloadNotificationPendingIntent())
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
        if (ongoing) {
            builder.setProgress(100, record.percent.coerceIn(0, 100), record.totalBytes <= 0L)
        }
        val notification = builder.build()

        try {
            manager.notify(DOWNLOAD_NOTIFICATION_ID, notification)
            Log.i(TAG, "DOWNLOAD_NOTIFICATION_POSTED name=${record.name} complete=${record.complete} percent=${record.percent}")
        } catch (error: SecurityException) {
            Log.w(TAG, "Download notification permission is unavailable", error)
        }
    }

    private fun renderDownloads() {
        if (!::downloadsList.isInitialized) return
        downloadsList.removeAllViews()
        downloadsList.addView(textView("Downloads", 24f, Color.WHITE, bold = true))
        downloadsList.addView(textView(
            "Files saved to Downloads",
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
        runCatching {
            val uri = if (record.path.startsWith("content://")) {
                Uri.parse(record.path)
            } else {
                val file = record.path.takeIf { it.isNotBlank() }?.let(::File)
                    ?: throw IllegalStateException("Downloaded file path is missing")
                if (!file.exists()) throw IllegalStateException("Downloaded file is missing")
                FileProvider.getUriForFile(this, "$packageName.files", file)
            }
            val isApk = DownloadFileSupport.isApk(record.name, record.mimeType)
            val needsInstallPermission = isApk && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !packageManager.canRequestPackageInstalls()
            val openIntent = if (isApk) {
                createApkInstallIntent(uri)
            } else {
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, record.mimeType.ifBlank { "application/octet-stream" })
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            startActivity(if (isApk) openIntent else Intent.createChooser(openIntent, "Open ${record.name}"))
            if (needsInstallPermission) {
                Toast.makeText(
                    this,
                    "Allow installs from Browser, then open the APK again",
                    Toast.LENGTH_LONG
                ).show()
            }
        }.onFailure {
            val message = if (DownloadFileSupport.isApk(record.name, record.mimeType)) {
                "Unable to open the APK installer"
            } else {
                "No app can open this file"
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            Log.e(TAG, "Unable to open downloaded file", it)
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
                put("mime", record.mimeType)
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
                    updatedAt = item.optLong("updated"),
                    mimeType = item.optString("mime", "application/octet-stream")
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
        const val REQUEST_OPEN_DOWNLOADS = 4106
        const val DOWNLOAD_NOTIFICATION_CHANNEL = "browser_downloads"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        const val KEY_AD_BLOCK = "ad_block_enabled"
        const val KEY_DOWNLOADS = "downloads_json"
        const val KEY_BROWSER_STATE = "browser_state"
        const val KEY_LAST_URL = "last_successful_url"
        const val KEY_NOTIFICATION_PERMISSION_ASKED = "download_notification_permission_asked"
        const val EXTRA_TEST_URL = "com.search.browser.TEST_URL"
        const val EXTRA_TEST_DOWNLOAD_PAGE = "com.search.browser.TEST_DOWNLOAD_PAGE"
        const val EXTRA_SHOW_DOWNLOADS = "com.search.browser.SHOW_DOWNLOADS"
        const val EXTRA_TEST_CRASH_RENDERER = "com.search.browser.TEST_CRASH_RENDERER"
        const val TAG = "Browser"
    }
}
