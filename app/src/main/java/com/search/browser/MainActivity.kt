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
import android.os.Handler
import android.os.Looper
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
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.cefrium.CefriumBrowser
import com.cefrium.CefriumChromeClient
import com.cefrium.CefriumDownloadClient
import com.cefrium.CefriumNavigationClient
import com.cefrium.CefriumPermission
import com.cefrium.CefriumPermissionRequest
import com.cefrium.CefriumRendererTerminationStatus
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private data class PendingWebPermission(
        val request: CefriumPermissionRequest,
        val runtimePermissions: List<String>
    )

    private data class BrowserWindow(
        val id: Int,
        var browser: CefriumBrowser,
        var title: String,
        var url: String,
        val incognito: Boolean
    )

    private lateinit var browser: CefriumBrowser
    private lateinit var rootLayout: LinearLayout
    private lateinit var bottomBar: View
    private lateinit var browserSlot: FrameLayout
    private lateinit var downloadsPanel: ScrollView
    private lateinit var downloadsSwipeRefresh: SwipeRefreshLayout
    private lateinit var downloadsList: LinearLayout
    private lateinit var searchField: EditText
    private lateinit var shieldButton: ImageButton
    private lateinit var rendererErrorPanel: FrameLayout
    private lateinit var defaultBrowserButton: TextView
    private lateinit var windowsButton: TextView

    private val browserWindows = mutableListOf<BrowserWindow>()
    private val downloadBrowserOwners = LinkedHashMap<Int, CefriumBrowser>()
    private val privateDownloadIds = mutableSetOf<Int>()
    private var activeWindowId = -1
    private var nextWindowId = 1
    private var browserToCloseAfterDownload: CefriumBrowser? = null

    private var rendererRecoveryAttempts = 0
    private var rendererRecoveryPending = false
    private var lastRequestedUrl = "about:blank"
    private var lastSuccessfulUrl = HOME_URL
    private var testCrashRenderer = false
    private var testCrashTriggered = false
    private val preferences by lazy { getSharedPreferences(PREFERENCES, MODE_PRIVATE) }
    private val downloadRecords = LinkedHashMap<Int, DownloadRecord>()
    private val activeDownloadItems = LinkedHashMap<Int, CefriumBrowser.DownloadItem>()
    private val downloadSuggestedNames = LinkedHashMap<Int, String>()
    private val selectedDownloadIds = linkedSetOf<Int>()
    private val pendingPublicSaveIds = mutableSetOf<Int>()
    private var adBlockingEnabled = true
    private var showingDownloads = false
    private var pageFullscreen = false
    private var pendingWebPermission: PendingWebPermission? = null
    private var permissionPrompt: AlertDialog? = null
    private var notificationPermissionRequested = false
    private var downloadKeepAliveStarted = false
    private var activityDestroying = false
    private var browserCloseDeferredForDownload = false
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationPermissionRequested = false
        if (granted) {
            downloadRecords.values.filterNot { privateDownloadIds.contains(it.id) }
                .maxByOrNull { it.updatedAt }
                ?.takeIf { it.path.startsWith("content://") || it.canceled || it.interrupted }
                ?.let(::publishDownloadNotification)
        }
    }
    private val webPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val pending = pendingWebPermission ?: return@registerForActivityResult
        pendingWebPermission = null
        permissionPrompt = null
        val granted = pending.runtimePermissions.all { permission ->
            results[permission] == true ||
                ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) pending.request.grant() else pending.request.deny()
    }
    private val browserRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateDefaultBrowserButton()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.enableEdgeToEdge(window)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        adBlockingEnabled = if (BuildConfig.TV_LITE) false else preferences.getBoolean(KEY_AD_BLOCK, true)
        lastSuccessfulUrl = preferences.getString(KEY_LAST_URL, HOME_URL).orEmpty()
            .takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: HOME_URL
        lastRequestedUrl = lastSuccessfulUrl
        restoreSavedDownloads()
        createDownloadNotificationChannel()
        buildLayout()
        installBackHandler()

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
            "CEF_ENGINE version=0.21.0 chromium=156.0.8078.96 " +
                "abi=" + android.os.Build.SUPPORTED_ABIS.joinToString()
        )
        updateShieldButton()
        renderDownloads()
    }

    private fun attachBrowser(
        initialUrl: String,
        savedState: Bundle? = null,
        incognito: Boolean = false,
        replaceWindowId: Int? = null,
        existingBrowser: CefriumBrowser? = null,
        loadInitialUrl: Boolean = true,
        previousBrowserAlreadyClosed: Boolean = false
    ) {
        val previousWindow = browserWindows.firstOrNull { it.id == activeWindowId }
        if (!previousBrowserAlreadyClosed && previousWindow != null && previousWindow.browser !== existingBrowser) {
            exitPageFullscreen()
        }
        if (!previousBrowserAlreadyClosed && previousWindow != null && previousWindow.browser !== existingBrowser &&
            !hasActiveDownloadForBrowser(previousWindow.browser)
        ) {
            previousWindow.browser.setFocus(false)
            previousWindow.browser.setMediaSessionEnabled(false)
            previousWindow.browser.onPause()
        }
        val instance = existingBrowser ?: CefriumBrowser.createWithSurface(this, incognito)
        val displayUrl = existingBrowser?.getUrl()?.takeIf(::isSupportedWebUrl) ?: initialUrl
        // Native pull-to-refresh triggers only after a downward overscroll at the page top.
        instance.setPullToRefreshEnabled(!BuildConfig.TV_LITE)
        Log.i(TAG, "PULL_TO_REFRESH_CONFIG native=${!BuildConfig.TV_LITE}")
        val windowId = replaceWindowId ?: nextWindowId++
        val window = browserWindows.firstOrNull { it.id == windowId }?.also {
            it.browser = instance
            it.title = "New tab"
            it.url = displayUrl
        } ?: BrowserWindow(windowId, instance, "New tab", displayUrl, incognito).also {
            browserWindows.add(it)
        }
        activeWindowId = windowId
        browser = instance
        lastRequestedUrl = initialUrl
        searchField.hint = if (incognito) "Incognito browsing" else "Search or enter address"
        searchField.setText(displayUrl)
        browserSlot.removeAllViews()
        updateWindowsButton()
        // Darken app chrome only. Keep each website in its own color scheme; never inject page recoloring.
        instance.setBackgroundColor(Color.WHITE)
        instance.setDarkMode(false)
        Log.i(TAG, "DARK_APP_CHROME_ENABLED websiteColorsUntouched=true")
        instance.setPageVisible(true)
        instance.setFocus(true)
        instance.setMediaSessionActivity(mediaSessionPendingIntent(windowId))
        instance.setMediaSessionEnabled(true)
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
                            background: "#fff",
                            color: "#202124",
                            overflow: "hidden"
                        });

                        const addDownloadButton = (id, label, fileName, mimeType, content, top, height = "45vh") => {
                            const button = document.createElement("button");
                            button.id = id;
                            button.textContent = label;
                            button.style.cssText = `position:fixed;left:0;width:100vw;top:${'$'}{top};height:${'$'}{height};font-size:20px;background:#f1f3f4;color:#202124;border:2px solid #dadce0;z-index:2147483647;`;
                            button.addEventListener("click", () => {
                                const dataUrl = `data:${'$'}{mimeType};charset=utf-8,${'$'}{encodeURIComponent(content)}`;
                                const link = document.createElement("a");
                                link.href = dataUrl;
                                link.download = fileName;
                                document.body.appendChild(link);
                                link.click();
                                link.remove();
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
                        addDownloadButton(
                            "download-background-test",
                            "Background download test",
                            "download-background-test.txt",
                            "text/plain",
                            "This download completed after the Browser task was removed.\n",
                            "90vh",
                            "9vh"
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
        if (!BuildConfig.TV_LITE) instance.setAdBlockEnabled(adBlockingEnabled)
        Log.i(TAG, "AD_BLOCK_CONFIG enabled=$adBlockingEnabled tvLite=${BuildConfig.TV_LITE}")
        browserSlot.addView(
            instance.getSurfaceContainer(),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        instance.setNavigationClient(object : CefriumNavigationClient {
            override fun onUrlChanged(browser: CefriumBrowser, url: String) {
                window.url = url
                if (activeWindowId == windowId) {
                    lastRequestedUrl = url
                    runOnUiThread {
                        if (activeWindowId == windowId && !searchField.hasFocus()) {
                            searchField.setText(url)
                        }
                    }
                }
                Log.i(TAG, "PAGE_URL_CHANGED window=$windowId incognito=${window.incognito} url=${if (window.incognito) "<private>" else url}")
            }

            override fun onLoadingStateChanged(
                browser: CefriumBrowser,
                isLoading: Boolean,
                canGoBack: Boolean,
                canGoForward: Boolean
            ) {
                Log.i(
                    TAG,
                    "PAGE_LOAD_STATE loading=$isLoading canGoBack=$canGoBack canGoForward=$canGoForward"
                )
                if (!isLoading) {
                    val isActiveWindow = activeWindowId == windowId
                    if (isActiveWindow) {
                        updateShieldButton()
                    }
                    Log.i(TAG, "AD_BLOCK_PAGE_STATS window=$windowId enabled=$adBlockingEnabled blockedRequests=${instance.getBlockedCount()}")
                    val pageUrl = instance.getUrl().orEmpty().ifBlank { window.url }
                    window.url = pageUrl
                    if (pageUrl.startsWith("https://") || pageUrl.startsWith("http://")) {
                        if (!window.incognito && isActiveWindow) {
                            lastSuccessfulUrl = pageUrl
                            preferences.edit().putString(KEY_LAST_URL, pageUrl).apply()
                        }
                        if (isActiveWindow) {
                            if (rendererRecoveryPending) {
                                rendererRecoveryPending = false
                                Log.i(TAG, "CHROMIUM_RECOVERY_COMPLETED url=$pageUrl")
                            }
                            scheduleRendererCrashTest(instance, pageUrl)
                        }
                    }
                    if (BuildConfig.DEBUG && isActiveWindow && !window.incognito) {
                        browserSlot.postDelayed({ captureAndVerifyPage(instance, pageUrl) }, 1000L)
                    }
                }
            }

            override fun onRenderProcessTerminated(
                browser: CefriumBrowser,
                status: CefriumRendererTerminationStatus,
                errorCode: Int
            ) {
                handleRendererTermination(browser, status, errorCode)
            }
        })

        instance.setChromeClient(object : CefriumChromeClient {
            override fun onTitleChanged(browser: CefriumBrowser, title: String) {
                window.title = title.ifBlank { "New tab" }
                runOnUiThread { if (activeWindowId == windowId) updateWindowsButton() }
                Log.i(TAG, "PAGE_TITLE window=$windowId title=${if (window.incognito) "<private>" else title}")
            }

            override fun onCreateWindow(
                browser: CefriumBrowser,
                popup: CefriumBrowser,
                url: String,
                userGesture: Boolean
            ): Boolean {
                if (browserWindows.size >= MAX_BROWSER_WINDOWS) {
                    popup.close()
                    Toast.makeText(this@MainActivity, "Close a tab before opening another", Toast.LENGTH_SHORT).show()
                    return true
                }
                val popupUrl = popup.getUrl().orEmpty().ifBlank { url }
                attachBrowser(
                    popupUrl,
                    incognito = window.incognito,
                    existingBrowser = popup,
                    loadInitialUrl = false
                )
                Log.i(TAG, "POPUP_TAB_OPENED parent=$windowId userGesture=$userGesture incognito=${window.incognito}")
                return true
            }

            override fun onCloseWindow(browser: CefriumBrowser, popup: CefriumBrowser) {
                val popupWindow = browserWindows.firstOrNull { it.browser === popup } ?: return
                closeWindow(popupWindow.id, closeBrowser = false)
                Log.i(TAG, "POPUP_TAB_CLOSED window=${popupWindow.id}")
            }

            override fun onPermissionRequest(browser: CefriumBrowser, request: CefriumPermissionRequest) {
                handleWebPermissionRequest(request)
            }

            override fun onPermissionRequestCanceled(browser: CefriumBrowser, request: CefriumPermissionRequest) {
                if (pendingWebPermission?.request === request) {
                    permissionPrompt?.dismiss()
                    permissionPrompt = null
                    pendingWebPermission = null
                }
            }

            override fun onFullscreenChanged(browser: CefriumBrowser, fullscreen: Boolean) {
                runOnUiThread {
                    if (activeWindowId == windowId && this@MainActivity.browser === browser) {
                        setPageFullscreen(fullscreen)
                    }
                }
            }
        })


        Log.i(TAG, "DOWNLOAD_HANDLER_REGISTERING")
        instance.setDownloadClient(object : CefriumDownloadClient {
            override fun onBeforeDownload(
                browser: CefriumBrowser,
                item: CefriumBrowser.DownloadItem,
                callback: CefriumBrowser.BeforeDownloadCallback
            ) {
                Log.i(
                    TAG,
                    "DOWNLOAD_BEFORE_CALLBACK id=${item.getId()} suggested=${if (window.incognito) "<private>" else item.getSuggestedName()} " +
                        "url=${if (window.incognito) "<private>" else item.getUrl()} mimeType=${item.getMimeType()}"
                )
                val suggestedName = item.getSuggestedName().orEmpty()
                val urlName = Uri.parse(item.getUrl().orEmpty()).lastPathSegment.orEmpty()
                val mimeType = item.getMimeType().orEmpty()
                val name = DownloadFileSupport.normalizedName(suggestedName, urlName, mimeType)
                val downloadId = item.getId()

                val otherActiveDownload = activeDownloadItems.entries.firstOrNull { (activeId, activeItem) ->
                    activeId != downloadId &&
                        !activeItem.isComplete() && !activeItem.isCanceled() && !activeItem.isInterrupted()
                }
                if (otherActiveDownload != null) {
                    Log.w(
                        TAG,
                        "DOWNLOAD_REQUEST_REJECTED id=$downloadId name=${if (window.incognito) "<private>" else name} " +
                            "reason=another_download_active activeId=${otherActiveDownload.key}"
                    )
                    callback.cancel()
                    return
                }
                val stagingDirectory = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: File(filesDir, "downloads")
                if (!stagingDirectory.exists() && !stagingDirectory.mkdirs()) {
                    Log.e(TAG, "DOWNLOAD_REQUEST_REJECTED id=$downloadId name=${if (window.incognito) "<private>" else name} reason=staging_directory_unavailable")
                    callback.cancel()
                    return
                }

                val target = File(stagingDirectory, "$downloadId-$name")
                target.delete()
                activeDownloadItems[downloadId] = item
                downloadBrowserOwners[downloadId] = instance
                if (window.incognito) privateDownloadIds.add(downloadId)
                downloadSuggestedNames[downloadId] = name
                if (!startDownloadKeepAliveService(name, privateMode = window.incognito)) {
                    activeDownloadItems.remove(downloadId)
                    downloadBrowserOwners.remove(downloadId)
                    privateDownloadIds.remove(downloadId)
                    downloadSuggestedNames.remove(downloadId)
                    Log.e(TAG, "DOWNLOAD_REQUEST_REJECTED id=$downloadId name=${if (window.incognito) "<private>" else name} reason=background_service_unavailable")
                    callback.cancel()
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Unable to keep this download running in the background", Toast.LENGTH_LONG).show()
                    }
                    return
                }
                if (!window.incognito) downloadRecords[downloadId]?.let { record ->
                    record.name = name
                    record.url = item.getUrl().orEmpty()
                    record.mimeType = DownloadFileSupport.mimeTypeFor(name, mimeType)
                    record.updatedAt = System.currentTimeMillis()
                    publishDownloadNotification(record)
                    persistDownloads()
                    if (showingDownloads) renderDownloads()
                }
                Log.i(TAG, "DOWNLOAD_REQUEST_ACCEPTED name=${if (window.incognito) "Private download" else name} id=$downloadId mimeType=${DownloadFileSupport.mimeTypeFor(name, mimeType)} target=${if (window.incognito) "<private>" else target.absolutePath}")
                if (BuildConfig.DEBUG && name == BACKGROUND_DOWNLOAD_TEST_FILE) {
                    Log.i(TAG, "DOWNLOAD_BACKGROUND_ALLOW_DELAYED id=$downloadId delayMs=$BACKGROUND_DOWNLOAD_TEST_DELAY_MS")
                    Handler(Looper.getMainLooper()).postDelayed({
                        Log.i(TAG, "DOWNLOAD_BACKGROUND_ALLOWING id=$downloadId")
                        callback.allow(target.absolutePath)
                    }, BACKGROUND_DOWNLOAD_TEST_DELAY_MS)
                } else {
                    callback.allow(target.absolutePath)
                }
            }

            override fun onDownloadUpdated(browser: CefriumBrowser, item: CefriumBrowser.DownloadItem) {
                val path = item.getFullPath().orEmpty()
                val downloadId = item.getId()
                val pathName = path.takeIf { it.isNotBlank() }?.let { File(it).name }
                    ?.removePrefix("$downloadId-")
                val suggestedName = downloadSuggestedNames[downloadId]
                    ?: item.getSuggestedName().orEmpty().ifBlank { pathName.orEmpty() }
                val urlName = Uri.parse(item.getUrl().orEmpty()).lastPathSegment.orEmpty()
                val mimeType = item.getMimeType().orEmpty()
                val name = DownloadFileSupport.normalizedName(suggestedName, urlName, mimeType)
                runOnUiThread {
                    activeDownloadItems[downloadId] = item
                    if (item.isComplete() || item.isCanceled() || item.isInterrupted()) {
                        Log.i(
                            TAG,
                            "DOWNLOAD_ITEM_TERMINAL id=$downloadId name=${if (window.incognito) "<private>" else name} complete=${item.isComplete()} " +
                                "canceled=${item.isCanceled()} interrupted=${item.isInterrupted()} " +
                                "reason=${item.getInterruption()} path=${if (window.incognito) "<private>" else path}"
                        )
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
                    maybeStopDownloadKeepAliveService()
                }
            }
        })
        Log.i(TAG, "DOWNLOAD_HANDLER_REGISTERED")

        if (loadInitialUrl && existingBrowser == null) {
            if (!incognito && savedState != null && initialUrl == lastSuccessfulUrl) {
                instance.restoreState(savedState)
            } else {
                instance.loadUrl(initialUrl)
            }
        }
        Log.i(
            TAG,
            "CEF_BROWSER_CREATED url=${if (incognito) "<private>" else initialUrl} incognito=$incognito activeRenderers=" +
                CefriumBrowser.activeRenderProcessCount()
        )
    }

    private fun mediaSessionPendingIntent(windowId: Int): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_MEDIA_WINDOW_ID, windowId)
        }
        return PendingIntent.getActivity(
            this,
            MEDIA_SESSION_REQUEST_CODE_BASE + windowId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun startDownloadKeepAliveService(name: String, privateMode: Boolean = false): Boolean {
        val displayName = if (privateMode) "Private download in progress" else name
        return runCatching {
            val started = DownloadKeepAliveService.start(this, displayName)
            if (started) {
                downloadKeepAliveStarted = true
                Log.i(TAG, "DOWNLOAD_KEEP_ALIVE_REQUESTED name=$displayName")
            }
            started
        }.onFailure {
            Log.e(TAG, "DOWNLOAD_KEEP_ALIVE_START_FAILED name=$displayName", it)
        }.getOrDefault(false)
    }

    private fun hasActiveDownloadWork(): Boolean =
        activeDownloadItems.values.any { !it.isComplete() && !it.isCanceled() && !it.isInterrupted() } ||
            pendingPublicSaveIds.isNotEmpty()

    private fun maybeStopDownloadKeepAliveService() {
        if (hasActiveDownloadWork()) return

        if (downloadKeepAliveStarted) {
            DownloadKeepAliveService.stop(this)
            downloadKeepAliveStarted = false
            Log.i(TAG, "DOWNLOAD_KEEP_ALIVE_RELEASED")
        }

        if (activityDestroying && browserCloseDeferredForDownload) {
            DownloadKeepAliveService.releaseBrowser(this)
            val retained = browserToCloseAfterDownload
            runCatching { retained?.close() }
                .onFailure { Log.e(TAG, "BROWSER_CLOSE_AFTER_DOWNLOAD_FAILED", it) }
            browserToCloseAfterDownload = null
            browserCloseDeferredForDownload = false
            Log.i(TAG, "BROWSER_CLOSED_AFTER_DOWNLOAD")
        }
        if (!activityDestroying) downloadBrowserOwners.clear()
    }

    private fun handleRendererTermination(
        failedBrowser: CefriumBrowser,
        status: CefriumRendererTerminationStatus,
        errorCode: Int
    ) {
        if (isFinishing || isDestroyed || showingDownloads ||
            !::browser.isInitialized || browser !== failedBrowser
        ) return

        val failedWindow = browserWindows.firstOrNull { it.id == activeWindowId }
        Log.e(
            TAG,
            "CHROMIUM_RENDERER_TERMINATED status=$status errorCode=$errorCode " +
                "url=${if (failedWindow?.incognito == true) "<private>" else lastRequestedUrl} activeRenderers=" +
                CefriumBrowser.activeRenderProcessCount()
        )

        if (rendererRecoveryAttempts >= 1) {
            rendererErrorPanel.visibility = View.VISIBLE
            return
        }

        rendererRecoveryAttempts++
        rendererRecoveryPending = true
        val currentWindow = browserWindows.firstOrNull { it.id == activeWindowId }
        val restoreUrl = (if (currentWindow?.incognito == true) currentWindow.url else lastSuccessfulUrl)
            .takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?: "about:blank"

        runCatching { failedBrowser.close() }
            .onFailure { Log.w(TAG, "Closing the terminated Chromium browser reported an error", it) }
        browserSlot.removeAllViews()
        rendererErrorPanel.visibility = View.GONE

        Log.i(TAG, "CHROMIUM_RECOVERY_STARTED restoreUrl=${if (currentWindow?.incognito == true) "<private>" else restoreUrl}")
        attachBrowser(
            restoreUrl,
            incognito = currentWindow?.incognito == true,
            replaceWindowId = currentWindow?.id,
            previousBrowserAlreadyClosed = true
        )
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
        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_APP_BACKGROUND)
        }
        setContentView(rootLayout)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val safeInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val imeBottom = if (insets.isVisible(WindowInsetsCompat.Type.ime())) {
                insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            } else {
                0
            }
            // Keep the browser viewport above the keyboard so focused page fields stay visible.
            view.setPadding(
                safeInsets.left,
                safeInsets.top,
                safeInsets.right,
                maxOf(safeInsets.bottom, imeBottom)
            )
            insets
        }

        val content = FrameLayout(this).apply { setBackgroundColor(COLOR_APP_BACKGROUND) }
        rootLayout.addView(content, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // Let Cefrium own browser gestures. Its native refresh only fires at the web-page top.
        browserSlot = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        content.addView(browserSlot, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        downloadsList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(24))
            setBackgroundColor(COLOR_APP_BACKGROUND)
        }
        downloadsPanel = ScrollView(this).apply {
            visibility = View.GONE
            isFillViewport = true
            setBackgroundColor(COLOR_APP_BACKGROUND)
            addView(downloadsList)
        }
        downloadsSwipeRefresh = SwipeRefreshLayout(this).apply {
            isEnabled = !BuildConfig.TV_LITE
            visibility = View.GONE
            setColorSchemeColors(COLOR_ACCENT)
            setProgressBackgroundColorSchemeColor(COLOR_BORDER)
            setOnChildScrollUpCallback { _, _ -> downloadsPanel.canScrollVertically(-1) }
            setOnRefreshListener { refreshDownloads() }
            addView(downloadsPanel, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }
        content.addView(downloadsSwipeRefresh, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        rendererErrorPanel = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(COLOR_APP_BACKGROUND)
            val message = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(28), dp(24), dp(28), dp(24))
                addView(textView("Page stopped unexpectedly", 18f, COLOR_TEXT_PRIMARY, bold = true))
                addView(textView(
                    "Chromium stopped rendering this page. Reload to continue.",
                    13f,
                    COLOR_TEXT_SECONDARY
                ).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(9), 0, dp(20))
                })
                addView(textView("RELOAD PAGE", 12f, COLOR_TEXT_PRIMARY, bold = true).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(20), dp(12), dp(20), dp(12))
                    background = roundedBackground(COLOR_ACCENT_SURFACE, dp(12))
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

        bottomBar = buildBottomBar()
        rootLayout.addView(bottomBar)
    }

    private fun buildBottomBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(10))
            setBackgroundColor(COLOR_APP_BACKGROUND)
        }

        val searchShell = FrameLayout(this).apply {
            background = roundedBackground(COLOR_SURFACE, dp(18))
        }
        searchField = EditText(this).apply {
            hint = "Search or enter address"
            setHintTextColor(COLOR_TEXT_SECONDARY)
            setTextColor(COLOR_TEXT_PRIMARY)
            textSize = 14f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            background = null
            setPadding(dp(15), 0, dp(12), 0)
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
            compoundDrawablePadding = dp(10)
            compoundDrawableTintList = ColorStateList.valueOf(COLOR_TEXT_SECONDARY)
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
        if (BuildConfig.TV_LITE) shieldButton.visibility = View.GONE
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

        windowsButton = textView("1", 16f, COLOR_TEXT_PRIMARY, bold = true).apply {
            gravity = Gravity.CENTER
            contentDescription = "Show all windows"
            background = roundedBackground(COLOR_SURFACE, dp(15))
            setOnClickListener { showWindowsDialog() }
        }
        bar.addView(windowsButton, LinearLayout.LayoutParams(dp(46), dp(54)).apply {
            marginStart = dp(6)
        })

        defaultBrowserButton = textView("⋮", 26f, COLOR_TEXT_PRIMARY, bold = true).apply {
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
                roundedBackground(COLOR_SURFACE, dp(15))
            }
            imageTintList = ColorStateList.valueOf(COLOR_TEXT_PRIMARY)
            setOnClickListener { action() }
        }
    }

    private fun updateWindowsButton() {
        if (!::windowsButton.isInitialized) return
        windowsButton.text = browserWindows.size.toString()
        val active = browserWindows.firstOrNull { it.id == activeWindowId }
        windowsButton.contentDescription = if (active?.incognito == true) {
            "${browserWindows.size} windows, incognito active"
        } else {
            "${browserWindows.size} windows"
        }
    }

    private fun handleWebPermissionRequest(request: CefriumPermissionRequest) {
        if (pendingWebPermission != null || request.permissions.isEmpty()) {
            request.deny()
            return
        }

        val requested = request.permissions
        if (requested.contains(CefriumPermission.OTHER) ||
            (requested.contains(CefriumPermission.NFC) &&
                !packageManager.hasSystemFeature(PackageManager.FEATURE_NFC))
        ) {
            request.deny()
            return
        }

        val runtimePermissions = mutableListOf<String>()
        val labels = requested.map { permission ->
            when (permission) {
                CefriumPermission.GEOLOCATION -> {
                    runtimePermissions += Manifest.permission.ACCESS_COARSE_LOCATION
                    "Location"
                }
                CefriumPermission.CAMERA -> {
                    runtimePermissions += Manifest.permission.CAMERA
                    "Camera"
                }
                CefriumPermission.MICROPHONE -> {
                    runtimePermissions += Manifest.permission.RECORD_AUDIO
                    "Microphone"
                }
                CefriumPermission.NOTIFICATIONS -> {
                    if (Build.VERSION.SDK_INT >= 33) {
                        runtimePermissions += Manifest.permission.POST_NOTIFICATIONS
                    }
                    "Notifications"
                }
                CefriumPermission.CLIPBOARD_READ -> "Clipboard access"
                CefriumPermission.NFC -> "NFC"
                CefriumPermission.OTHER -> "Other"
            }
        }
        if (labels.isEmpty() || labels.contains("Other")) {
            request.deny()
            return
        }

        val pending = PendingWebPermission(request, runtimePermissions.distinct())
        pendingWebPermission = pending
        permissionPrompt = AlertDialog.Builder(this)
            .setTitle("Allow site access?")
            .setMessage("${request.origin} wants to use: ${labels.joinToString()}. You can block this request.")
            .setPositiveButton("Allow") { _, _ ->
                permissionPrompt = null
                val missing = pending.runtimePermissions.filter { permission ->
                    ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
                }
                if (missing.isEmpty()) {
                    pendingWebPermission = null
                    request.grant()
                } else {
                    webPermissionLauncher.launch(missing.toTypedArray())
                }
            }
            .setNegativeButton("Block") { _, _ ->
                permissionPrompt = null
                pendingWebPermission = null
                request.deny()
            }
            .setOnCancelListener {
                if (pendingWebPermission?.request === request) {
                    permissionPrompt = null
                    pendingWebPermission = null
                    request.deny()
                }
            }
            .create()
        permissionPrompt?.show()
    }

    private fun exitPageFullscreen() {
        if (!pageFullscreen) return
        if (::browser.isInitialized) {
            runCatching { browser.exitFullscreen() }
                .onFailure { Log.w(TAG, "Unable to exit page fullscreen", it) }
        }
        setPageFullscreen(false)
    }

    private fun setPageFullscreen(fullscreen: Boolean) {
        if (pageFullscreen == fullscreen) return
        pageFullscreen = fullscreen
        if (::bottomBar.isInitialized) {
            bottomBar.visibility = if (fullscreen) View.GONE else View.VISIBLE
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (fullscreen) hide(WindowInsetsCompat.Type.systemBars())
            else show(WindowInsetsCompat.Type.systemBars())
        }
        if (::rootLayout.isInitialized) ViewCompat.requestApplyInsets(rootLayout)
    }

    private fun installBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    pageFullscreen -> {
                        exitPageFullscreen()
                    }
                    showingDownloads -> showBrowser()
                    ::browser.isInitialized && browser.canGoBack() -> browser.goBack()
                    browserWindows.size > 1 -> closeWindow(activeWindowId)
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })
    }

    private fun showWindowsDialog() {
        if (browserWindows.isEmpty()) return
        val windows = browserWindows.toList()
        val labels = windows.map { item ->
            val activeMark = if (item.id == activeWindowId) "● " else ""
            val mode = if (item.incognito) " · Private" else ""
            val name = BrowserWindowLabel.shortName(item.title)
            val subtitle = BrowserWindowLabel.subtitle(item.url, item.incognito)
            "$activeMark$name$mode\n$subtitle"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("All windows (${windows.size})")
            .setItems(labels) { _, index -> switchWindow(windows[index].id) }
            .setPositiveButton("New window") { _, _ -> createNewWindow(incognito = false) }
            .setNeutralButton("New incognito") { _, _ -> createNewWindow(incognito = true) }
            .setNegativeButton("Close current") { _, _ -> closeWindow(activeWindowId) }
            .show()
    }

    private fun hasActiveDownloadForBrowser(target: CefriumBrowser): Boolean =
        downloadBrowserOwners.any { (id, owner) ->
            owner === target && (activeDownloadItems.containsKey(id) || pendingPublicSaveIds.contains(id))
        }

    private fun createNewWindow(incognito: Boolean) {
        if (browserWindows.size >= MAX_BROWSER_WINDOWS) {
            Toast.makeText(this, "Close a tab before opening another", Toast.LENGTH_SHORT).show()
            return
        }
        showBrowser()
        browserSlot.removeAllViews()
        rendererErrorPanel.visibility = View.GONE
        attachBrowser(HOME_URL, incognito = incognito)
    }

    private fun switchWindow(windowId: Int) {
        val target = browserWindows.firstOrNull { it.id == windowId } ?: return
        if (windowId == activeWindowId) return

        browserWindows.firstOrNull { it.id == activeWindowId }?.browser?.let { current ->
            if (!hasActiveDownloadForBrowser(current)) {
                current.setFocus(false)
                current.setMediaSessionEnabled(false)
                current.onPause()
            }
        }
        exitPageFullscreen()
        browserSlot.removeAllViews()
        activeWindowId = target.id
        browser = target.browser
        target.browser.setMediaSessionEnabled(true)
        target.browser.setFocus(true)
        target.browser.setPageVisible(true)
        target.browser.onResume()
        browserSlot.addView(target.browser.getSurfaceContainer(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        rendererErrorPanel.visibility = View.GONE
        lastRequestedUrl = target.url
        if (!target.incognito && target.url.startsWith("http")) lastSuccessfulUrl = target.url
        searchField.hint = if (target.incognito) "Incognito browsing" else "Search or enter address"
        searchField.setText(target.url)
        showBrowser()
        updateWindowsButton()
        updateShieldButton()
    }

    private fun closeWindow(windowId: Int, closeBrowser: Boolean = true) {
        val target = browserWindows.firstOrNull { it.id == windowId } ?: return
        if (hasActiveDownloadForBrowser(target.browser)) {
            Toast.makeText(this, "Wait for this window's download to finish", Toast.LENGTH_SHORT).show()
            return
        }

        val wasActive = target.id == activeWindowId
        if (wasActive) exitPageFullscreen()
        if (wasActive) {
            val replacement = browserWindows.firstOrNull { it.id != target.id }
            if (replacement != null) {
                switchWindow(replacement.id)
            } else {
                browserSlot.removeAllViews()
                activeWindowId = -1
            }
        }
        browserWindows.removeAll { it.id == target.id }
        if (closeBrowser) {
            runCatching { target.browser.close() }
                .onFailure { Log.w(TAG, "Unable to close browser window id=${target.id}", it) }
        }

        if (target.incognito && browserWindows.none { it.incognito }) {
            privateDownloadIds.toList().forEach { id ->
                if (!activeDownloadItems.containsKey(id) && !pendingPublicSaveIds.contains(id)) {
                    downloadRecords.remove(id)
                    downloadSuggestedNames.remove(id)
                    privateDownloadIds.remove(id)
                }
            }
            persistDownloads()
            if (showingDownloads) renderDownloads()
            Log.i(TAG, "INCOGNITO_SESSION_CLOSED")
        }

        if (browserWindows.isEmpty()) attachBrowser(HOME_URL)
        updateWindowsButton()
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
        browserWindows.firstOrNull { it.id == activeWindowId }?.url = target
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
        val currentWindow = browserWindows.firstOrNull { it.id == activeWindowId }
        val restoreUrl = currentWindow?.url?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?: lastSuccessfulUrl
        runCatching { oldBrowser.close() }
            .onFailure { Log.w(TAG, "Closing browser before manual retry reported an error", it) }
        browserSlot.removeAllViews()
        attachBrowser(
            restoreUrl,
            incognito = currentWindow?.incognito == true,
            replaceWindowId = currentWindow?.id
        )
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
                    browserRoleLauncher.launch(
                        roleManager.createRequestRoleIntent(RoleManager.ROLE_BROWSER)
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
        val mediaWindowId = intent.getIntExtra(EXTRA_MEDIA_WINDOW_ID, -1)
        if (mediaWindowId >= 0) {
            if (browserWindows.any { it.id == mediaWindowId }) {
                switchWindow(mediaWindowId)
                showBrowser()
            }
            return
        }
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
        browserWindows.firstOrNull { it.id == activeWindowId }?.url = url
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
        val blockedCount = if (::browser.isInitialized) browser.getBlockedCount() else 0
        shieldButton.contentDescription = if (adBlockingEnabled) {
            "Ad blocker on, $blockedCount requests blocked"
        } else {
            "Ad blocker off"
        }
    }

    private fun toggleDownloads() {
        if (showingDownloads) showBrowser() else showDownloadsPanel()
    }

    private fun showDownloadsPanel() {
        showingDownloads = true
        downloadsPanel.visibility = View.VISIBLE
        downloadsSwipeRefresh.visibility = View.VISIBLE
        searchField.clearFocus()
        renderDownloads()
    }

    private fun showBrowser() {
        showingDownloads = false
        selectedDownloadIds.clear()
        downloadsSwipeRefresh.visibility = View.GONE
        downloadsPanel.visibility = View.GONE
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
        val isPrivateDownload = privateDownloadIds.contains(downloadId)
        if (!isPrivateDownload) requestDownloadNotificationPermissionIfNeeded()
        val item = activeDownloadItems[downloadId]
        val id = downloadId
        val record = downloadRecords[id] ?: DownloadRecord(
            id = id,
            name = downloadSuggestedNames[downloadId] ?: name.ifBlank { "Download" },
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

        record.name = downloadSuggestedNames[downloadId] ?: name.ifBlank { record.name }
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
        if (record.canceled) deleteCanceledPartialDownload(record)
        if (record.complete && !record.path.startsWith("content://")) {
            persistDownloads()
            if (showingDownloads) renderDownloads()
            saveCompletedDownloadToPublicDownloads(record)
            return
        }

        if (!isPrivateDownload) publishDownloadNotification(record)
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
            Log.e(TAG, "DOWNLOAD_FILE_SAVE_FAILED name=${if (privateDownloadIds.contains(record.id)) "<private>" else record.name} reason=missing_source")
            maybeStopDownloadKeepAliveService()
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
                    Log.i(TAG, "Downloaded ZIP is a regular file, not a single-APK archive: ${if (privateDownloadIds.contains(record.id)) "<private>" else record.name}")
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
                    Log.w(TAG, "Temporary download could not be deleted: " + if (privateDownloadIds.contains(record.id)) "<private>" else downloadedFile.absolutePath)
                }
            } catch (error: Throwable) {
                failure = error
                destinationUri?.let { runCatching { contentResolver.delete(it, null, null) } }
                if (privateDownloadIds.contains(record.id)) {
                    Log.e(TAG, "Unable to save private downloaded file")
                } else {
                    Log.e(TAG, "Unable to save the downloaded file", error)
                }
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
                    Log.i(TAG, "DOWNLOAD_FILE_SAVED name=${if (privateDownloadIds.contains(record.id)) "<private>" else record.name} mimeType=$mimeType bytes=${record.receivedBytes} uri=${if (privateDownloadIds.contains(record.id)) "<private>" else savedUri}")
                } else {
                    record.complete = false
                    record.interrupted = true
                    Log.e(TAG, "DOWNLOAD_FILE_SAVE_FAILED name=${if (privateDownloadIds.contains(record.id)) "<private>" else record.name}")
                }
                record.updatedAt = System.currentTimeMillis()
                val privateDownload = privateDownloadIds.contains(record.id)
                if (!privateDownload) {
                    publishDownloadNotification(record)
                    persistDownloads()
                }
                downloadSuggestedNames.remove(record.id)
                if (showingDownloads) renderDownloads()
                maybeStopDownloadKeepAliveService()
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
        return Intent(Intent.ACTION_VIEW).apply {
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
        if (privateDownloadIds.contains(record.id)) return
        if (!requestDownloadNotificationPermissionIfNeeded()) return
        val manager = NotificationManagerCompat.from(this)
        if (!manager.areNotificationsEnabled()) return

        val ongoing = !record.complete && !record.canceled && !record.interrupted && !record.paused
        val title = when {
            record.complete -> "Download complete"
            record.canceled -> "Download canceled"
            record.interrupted -> "Download failed"
            record.paused -> "Download paused"
            else -> "Downloading ${record.name}"
        }
        val detail = when {
            record.complete -> "${record.name} saved to Downloads"
            record.canceled -> record.name
            record.interrupted -> "${record.name} could not be saved"
            record.paused -> "${record.name} · ${record.percent}% paused"
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
            if (privateDownloadIds.contains(record.id)) return
            manager.notify(DOWNLOAD_NOTIFICATION_ID, notification)
            Log.i(TAG, "DOWNLOAD_NOTIFICATION_POSTED name=${record.name} complete=${record.complete} percent=${record.percent}")
        } catch (error: SecurityException) {
            Log.w(TAG, "Download notification permission is unavailable", error)
        }
    }

    private fun renderDownloads() {
        if (!::downloadsList.isInitialized) return
        val privateMode = browserWindows.firstOrNull { it.id == activeWindowId }?.incognito == true
        val visibleRecords = downloadRecords.values.filter {
            privateDownloadIds.contains(it.id) == privateMode
        }
        val visibleIds = visibleRecords.mapTo(mutableSetOf()) { it.id }
        selectedDownloadIds.retainAll(visibleIds)
        downloadsList.removeAllViews()

        if (selectedDownloadIds.isNotEmpty()) {
            val selectionBar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            selectionBar.addView(textView(
                "${selectedDownloadIds.size} selected", 18f, COLOR_TEXT_PRIMARY, bold = true
            ), LinearLayout.LayoutParams(0, dp(42), 1f))
            selectionBar.addView(
                downloadActionButton("Delete", COLOR_DANGER_SURFACE) {
                    confirmDeleteSelectedDownloads()
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40))
            )
            selectionBar.addView(
                downloadActionButton("Cancel", COLOR_BUTTON_SURFACE) {
                    selectedDownloadIds.clear()
                    renderDownloads()
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply {
                    marginStart = dp(8)
                }
            )
            downloadsList.addView(selectionBar)
            downloadsList.addView(textView(
                "Tap other download entries to add them to the selection.",
                12f,
                COLOR_TEXT_SECONDARY
            ).apply { setPadding(0, dp(7), 0, dp(22)) })
        } else {
            downloadsList.addView(textView("Downloads", 24f, COLOR_TEXT_PRIMARY, bold = true))
        }

        if (visibleRecords.isEmpty()) {
            downloadsList.addView(textView(
                "No downloads yet", 14f, COLOR_TEXT_SECONDARY
            ).apply { setPadding(0, dp(24), 0, 0) })
            return
        }

        visibleRecords.sortedByDescending { it.updatedAt }
            .forEach { downloadsList.addView(createDownloadCard(it)) }
    }

    private fun createDownloadCard(record: DownloadRecord): View {
        val isSelected = selectedDownloadIds.contains(record.id)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(12))
            background = roundedBackground(
                if (isSelected) COLOR_ACCENT_SURFACE else COLOR_CARD,
                dp(14)
            )
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(textView(
            (if (isSelected) "✓  " else "") + record.name,
            14f,
            COLOR_TEXT_PRIMARY,
            bold = true
        ).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }, LinearLayout.LayoutParams(0, -2, 1f))

        val isReadyToSelect = record.complete && !pendingPublicSaveIds.contains(record.id)
        val canSelectForDeletion = record.isSelectableForDeletion(
            pendingPublicSaveIds.contains(record.id)
        )
        val statusText = when {
            record.complete && !isReadyToSelect -> "SAVING"
            record.complete -> "DONE"
            record.canceled -> "CANCELED"
            record.interrupted -> "INTERRUPTED"
            record.paused -> "PAUSED"
            else -> "${record.percent}%"
        }
        titleRow.addView(textView(statusText, 10f, COLOR_TEXT_SECONDARY, bold = true))
        card.addView(titleRow)

        val detail = when {
            record.complete && !isReadyToSelect -> "Saving to Downloads…"
            record.complete -> "Completed · ${formatBytes(record.receivedBytes)}"
            record.canceled -> "Canceled"
            record.interrupted -> "Download interrupted"
            record.paused -> "Paused · ${formatBytes(record.receivedBytes)} received"
            else -> "${formatSpeed(record.speedBytesPerSecond)} · ${formatBytes(record.receivedBytes)} of ${formatBytes(record.totalBytes)}"
        }
        card.addView(textView(detail, 12f, COLOR_TEXT_SECONDARY).apply {
            setPadding(0, dp(8), 0, dp(10))
        })

        if (!record.complete && !record.canceled && !record.interrupted) {
            val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                this.progress = record.percent
                progressTintList = ColorStateList.valueOf(COLOR_TEXT_PRIMARY)
                progressBackgroundTintList = ColorStateList.valueOf(COLOR_BORDER)
            }
            card.addView(progress, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(4)
            ))

        } else if (canSelectForDeletion) {
            card.isClickable = true
            card.isLongClickable = true
            card.setOnClickListener {
                if (selectedDownloadIds.isNotEmpty() || !isReadyToSelect) {
                    toggleDownloadSelection(record.id)
                } else {
                    openDownloadedFile(record)
                }
            }
            card.setOnLongClickListener {
                toggleDownloadSelection(record.id)
                true
            }
        }

        if (!record.complete && !record.canceled && !record.interrupted && findLiveDownload(record) != null) {
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val primaryLabel = if (record.paused) "Resume" else "Pause"
            val primaryAction: () -> Unit = if (record.paused) {
                { resumeDownload(record) }
            } else {
                { pauseDownload(record) }
            }
            actions.addView(
                downloadActionButton(primaryLabel, COLOR_BUTTON_SURFACE, primaryAction),
                LinearLayout.LayoutParams(0, dp(42), 1f)
            )
            actions.addView(
                downloadActionButton("Cancel", COLOR_DANGER_SURFACE) { cancelDownload(record) },
                LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(9) }
            )
            card.addView(actions, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42)
            ).apply { topMargin = dp(12) })
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(10))
            addView(card)
        }
    }

    private fun downloadActionButton(
        label: String,
        backgroundColor: Int,
        action: () -> Unit
    ): TextView = textView(
        label, 12f, if (backgroundColor == COLOR_DANGER_SURFACE) COLOR_DANGER_TEXT else COLOR_TEXT_PRIMARY, bold = true
    ).apply {
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = roundedBackground(backgroundColor, dp(10))
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun toggleDownloadSelection(downloadId: Int) {
        val record = downloadRecords[downloadId] ?: return
        if (!record.isSelectableForDeletion(pendingPublicSaveIds.contains(downloadId))) return
        if (!selectedDownloadIds.add(downloadId)) selectedDownloadIds.remove(downloadId)
        renderDownloads()
    }

    private fun confirmDeleteSelectedDownloads() {
        val selected = selectedDownloadIds.mapNotNull(downloadRecords::get)
        confirmDeleteDownloads(selected)
    }

    private fun confirmDeleteDownloads(records: List<DownloadRecord>) {
        val deletable = records.filter {
            it.isSelectableForDeletion(pendingPublicSaveIds.contains(it.id))
        }
        if (deletable.isEmpty()) return

        val title = if (deletable.size == 1) "Delete download?" else "Delete ${deletable.size} downloads?"
        val message = if (deletable.size == 1) {
            "${deletable.first().name} and its temporary or saved file will be deleted."
        } else {
            "The ${deletable.size} selected downloads and their temporary or saved files will be deleted."
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> deleteSelectedDownloads(deletable) }
            .show()
    }

    private fun deleteSelectedDownloads(records: List<DownloadRecord>) {
        if (records.isEmpty()) return
        downloadsSwipeRefresh.isEnabled = false
        Thread({
            val deletedIds = mutableListOf<Int>()
            val failedNames = mutableListOf<String>()
            records.forEach { record ->
                if (deleteDownloadRecordFile(record)) deletedIds += record.id
                else failedNames += record.name
            }
            runOnUiThread {
                deletedIds.forEach { id ->
                    downloadRecords.remove(id)
                    selectedDownloadIds.remove(id)
                }
                selectedDownloadIds.retainAll(downloadRecords.keys)
                persistDownloads()
                downloadsSwipeRefresh.isEnabled = true

                val latestRecord = downloadRecords.values.maxByOrNull { it.updatedAt }
                if (latestRecord != null) publishDownloadNotification(latestRecord)
                else NotificationManagerCompat.from(this).cancel(DOWNLOAD_NOTIFICATION_ID)

                renderDownloads()
                val message = when {
                    failedNames.isEmpty() -> "${deletedIds.size} file(s) deleted"
                    deletedIds.isEmpty() -> "Unable to delete the selected file(s)"
                    else -> "${deletedIds.size} deleted · ${failedNames.size} could not be deleted"
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                if (failedNames.isNotEmpty()) {
                    Log.w(TAG, "DOWNLOAD_DELETE_FAILED names=${failedNames.joinToString()}")
                }
            }
        }, "delete-downloaded-files").start()
    }

    private fun deleteDownloadRecordFile(record: DownloadRecord): Boolean {
        if (record.path.isBlank()) return true
        return runCatching {
            if (record.path.startsWith("content://")) {
                val uri = Uri.parse(record.path)
                contentResolver.delete(uri, null, null) > 0 || !isDownloadFileAvailable(record)
            } else {
                val file = File(record.path).canonicalFile
                val stagingDirectory = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: File(filesDir, "downloads")
                val isOwnedStagingFile = file.parentFile == stagingDirectory.canonicalFile &&
                    file.name.startsWith("${record.id}-")
                if (!isOwnedStagingFile) {
                    Log.w(TAG, "Refusing to delete download outside app-owned staging storage id=${record.id}")
                    false
                } else {
                    !file.exists() || file.delete()
                }
            }
        }.onFailure {
            Log.e(TAG, "Unable to delete download record id=${record.id}", it)
        }.getOrDefault(false)
    }

    private fun isDownloadFileAvailable(record: DownloadRecord): Boolean {
        if (record.path.isBlank()) return false
        return runCatching {
            if (record.path.startsWith("content://")) {
                contentResolver.openFileDescriptor(Uri.parse(record.path), "r")?.use { true } == true
            } else {
                File(record.path).isFile
            }
        }.getOrDefault(false)
    }

    private fun refreshDownloads() {
        val completedSnapshot = downloadRecords.values
            .filter { it.complete && !pendingPublicSaveIds.contains(it.id) }
            .toList()
        Thread({
            val missingIds = completedSnapshot
                .filterNot(::isDownloadFileAvailable)
                .map { it.id }
            runOnUiThread {
                missingIds.forEach { id ->
                    downloadRecords.remove(id)
                    selectedDownloadIds.remove(id)
                }
                persistDownloads()
                renderDownloads()
                downloadsSwipeRefresh.isRefreshing = false
            }
        }, "refresh-downloads").start()
    }

    private fun findLiveDownload(record: DownloadRecord): CefriumBrowser.DownloadItem? =
        activeDownloadItems[record.id] ?: record.liveItem

    private fun pauseDownload(record: DownloadRecord) {
        val item = findLiveDownload(record)
        if (item == null || record.complete || record.canceled || record.interrupted) {
            Toast.makeText(this, "This download can no longer be paused", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { item.pause() }
            .onSuccess {
                record.paused = true
                record.speedBytesPerSecond = 0L
                record.updatedAt = System.currentTimeMillis()
                publishDownloadNotification(record)
                persistDownloads()
                if (showingDownloads) renderDownloads()
                Log.i(TAG, "DOWNLOAD_PAUSE_REQUESTED id=${record.id} name=${record.name}")
            }
            .onFailure {
                Log.e(TAG, "Unable to pause download id=${record.id}", it)
                Toast.makeText(this, "Unable to pause download", Toast.LENGTH_SHORT).show()
            }
    }

    private fun resumeDownload(record: DownloadRecord) {
        val item = findLiveDownload(record)
        if (item == null || !record.paused || record.complete || record.canceled || record.interrupted) {
            Toast.makeText(this, "This download cannot be resumed", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { item.resume() }
            .onSuccess {
                record.paused = false
                record.updatedAt = System.currentTimeMillis()
                publishDownloadNotification(record)
                persistDownloads()
                if (showingDownloads) renderDownloads()
                Log.i(TAG, "DOWNLOAD_RESUME_REQUESTED id=${record.id} name=${record.name}")
            }
            .onFailure {
                Log.e(TAG, "Unable to resume download id=${record.id}", it)
                Toast.makeText(this, "Unable to resume download", Toast.LENGTH_SHORT).show()
            }
    }

    private fun cancelDownload(record: DownloadRecord) {
        val item = findLiveDownload(record)
        if (item == null || record.complete || record.canceled || record.interrupted) {
            Toast.makeText(this, "This download can no longer be canceled", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { item.cancel() }
            .onSuccess {
                record.canceled = true
                record.paused = false
                record.speedBytesPerSecond = 0L
                record.updatedAt = System.currentTimeMillis()
                publishDownloadNotification(record)
                persistDownloads()
                if (showingDownloads) renderDownloads()
                Log.i(TAG, "DOWNLOAD_CANCEL_REQUESTED id=${record.id} name=${record.name}")
            }
            .onFailure {
                Log.e(TAG, "Unable to cancel download id=${record.id}", it)
                Toast.makeText(this, "Unable to cancel download", Toast.LENGTH_SHORT).show()
            }
    }

    private fun deleteCanceledPartialDownload(record: DownloadRecord) {
        val path = record.path.takeIf { it.isNotBlank() && !it.startsWith("content://") } ?: return
        val partialFile = File(path)
        if (!partialFile.name.startsWith("${record.id}-")) return
        val stagingDirectory = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(filesDir, "downloads")
        val isOwnedStagingFile = runCatching {
            partialFile.canonicalFile.parentFile == stagingDirectory.canonicalFile
        }.getOrDefault(false)
        if (isOwnedStagingFile && partialFile.exists() && !partialFile.delete()) {
            Log.w(TAG, "Unable to delete canceled partial download: ${partialFile.absolutePath}")
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
        downloadRecords.values.filterNot { privateDownloadIds.contains(it.id) }.forEach { record ->
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
                    interrupted = item.optBoolean("interrupted") || (!complete && !DownloadKeepAliveService.isRunning),
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

    private fun roundedBackground(fill: Int, radius: Int): android.graphics.drawable.Drawable =
        if (BuildConfig.TV_LITE) {
            android.graphics.drawable.ColorDrawable(fill)
        } else {
            GradientDrawable().apply {
                setColor(fill)
                cornerRadius = radius.toFloat()
            }
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

    override fun onPause() {
        // Keep only browser instances that own an active transfer running.
        exitPageFullscreen()
        browserWindows.forEach { window ->
            if (!hasActiveDownloadForBrowser(window.browser)) {
                window.browser.setFocus(false)
                window.browser.onPause()
            }
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        browserWindows.forEach { window ->
            if (window.id == activeWindowId) {
                window.browser.setMediaSessionEnabled(true)
                window.browser.setFocus(true)
                window.browser.setPageVisible(true)
                window.browser.onResume()
            } else if (!hasActiveDownloadForBrowser(window.browser)) {
                window.browser.setMediaSessionEnabled(false)
                window.browser.setFocus(false)
                window.browser.onPause()
            }
        }
        if (DownloadKeepAliveService.isRunning) {
            restoreSavedDownloads()
            renderDownloads()
            Log.i(TAG, "DOWNLOADS_RESTORED_WHILE_BACKGROUND_TRANSFER_ACTIVE")
        }
        updateDefaultBrowserButton()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val activeWindow = browserWindows.firstOrNull { it.id == activeWindowId }
        if (activeWindow != null && !activeWindow.incognito) {
            outState.putBundle(KEY_BROWSER_STATE, activeWindow.browser.saveState())
            preferences.edit().putString(KEY_LAST_URL, lastSuccessfulUrl).apply()
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        activityDestroying = true
        if (hasActiveDownloadWork() && downloadKeepAliveStarted) {
            // The service owns the one browser instance whose download callback is still active.
            val retained = downloadBrowserOwners.entries.firstOrNull { (id, _) ->
                activeDownloadItems.containsKey(id) || pendingPublicSaveIds.contains(id)
            }?.value ?: browser
            DownloadKeepAliveService.retainBrowser(this, retained)
            browserToCloseAfterDownload = retained
            browserCloseDeferredForDownload = true
            browserWindows.forEach { window ->
                if (window.browser !== retained) runCatching { window.browser.close() }
            }
            Log.i(TAG, "BROWSER_CLOSE_DEFERRED_FOR_DOWNLOAD pendingSave=${pendingPublicSaveIds.isNotEmpty()}")
        } else {
            if (downloadKeepAliveStarted) DownloadKeepAliveService.stop(this)
            DownloadKeepAliveService.releaseBrowser(this)
            downloadKeepAliveStarted = false
            browserWindows.forEach { window ->
                runCatching { window.browser.close() }
            }
        }
        browserWindows.clear()
        super.onDestroy()
    }

    private companion object {
        val COLOR_APP_BACKGROUND = 0xFF121418.toInt()
        val COLOR_SURFACE = 0xFF1C222B.toInt()
        val COLOR_CARD = 0xFF202731.toInt()
        val COLOR_BUTTON_SURFACE = 0xFF303945.toInt()
        val COLOR_TEXT_PRIMARY = 0xFFF1F4F8.toInt()
        val COLOR_TEXT_SECONDARY = 0xFFB2BBC7.toInt()
        val COLOR_BORDER = 0xFF424B59.toInt()
        val COLOR_ACCENT = 0xFF8AB4F8.toInt()
        val COLOR_ACCENT_SURFACE = 0xFF263B59.toInt()
        val COLOR_DANGER_SURFACE = 0xFF4B292C.toInt()
        val COLOR_DANGER_TEXT = 0xFFFFB4AB.toInt()
        const val MAX_BROWSER_WINDOWS = 8
        const val MEDIA_SESSION_REQUEST_CODE_BASE = 5100
        const val EXTRA_MEDIA_WINDOW_ID = "com.search.browser.MEDIA_WINDOW_ID"
        const val PREFERENCES = "browser_preferences"
        const val HOME_URL = "https://www.google.com/"
        const val DOWNLOAD_NOTIFICATION_ID = 4103
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
        const val BACKGROUND_DOWNLOAD_TEST_FILE = "download-background-test.txt"
        const val BACKGROUND_DOWNLOAD_TEST_DELAY_MS = 8_000L
        const val TAG = "Browser"
    }
}
