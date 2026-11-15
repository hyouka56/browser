package com.search.browser

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.webkit.WebViewCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var browserView: WebView
    private lateinit var browserSlot: FrameLayout
    private lateinit var downloadsPanel: ScrollView
    private lateinit var downloadsList: LinearLayout
    private lateinit var searchField: EditText
    private lateinit var shieldButton: ImageButton
    private lateinit var rendererErrorPanel: FrameLayout
    private lateinit var rendererErrorMessage: TextView
    private lateinit var pageProgress: ProgressBar

    @Volatile
    private var adBlockingEnabled = true
    private var showingDownloads = false
    private var lastSuccessfulUrl = "about:blank"
    private var rendererRecoveryPending = false
    private var testCrashRenderer = false
    private var testCrashTriggered = false

    private val preferences by lazy {
        getSharedPreferences(PREFERENCES, MODE_PRIVATE)
    }
    private val downloadManager by lazy {
        getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    }
    private val downloadRecords = LinkedHashMap<Long, DownloadRecord>()
    private val downloadHandler = Handler(Looper.getMainLooper())

    private val downloadRefresh = object : Runnable {
        override fun run() {
            if (!showingDownloads || isFinishing || isDestroyed) return
            refreshDownloadRecords()
            renderDownloads()
            downloadHandler.postDelayed(this, DOWNLOAD_REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        adBlockingEnabled = preferences.getBoolean(KEY_AD_BLOCK, true)
        restoreSavedDownloads()
        buildLayout()

        val provider = WebViewCompat.getCurrentWebViewPackage(this)
        Log.i(
            TAG,
            "WEBVIEW_PROVIDER package=${provider?.packageName ?: "unavailable"} " +
                "version=${provider?.versionName ?: "unavailable"}"
        )
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        browserView = createBrowserView()
        browserSlot.addView(
            browserView,
            0,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        testCrashRenderer = BuildConfig.DEBUG &&
            intent.getBooleanExtra(EXTRA_TEST_CRASH_RENDERER, false)

        val testUrl = if (BuildConfig.DEBUG) {
            intent.getStringExtra(EXTRA_TEST_URL)?.takeIf { it.isNotBlank() }
        } else {
            null
        }

        when {
            testUrl != null -> browserView.loadUrl(testUrl)
            savedInstanceState != null && browserView.restoreState(savedInstanceState) != null -> {
                Log.i(TAG, "WEBVIEW_STATE_RESTORED url=${browserView.url.orEmpty()}")
            }
            else -> browserView.loadUrl("about:blank")
        }

        updateShieldButton()
        renderDownloads()
    }

    private fun createBrowserView(): WebView {
        return WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                loadsImagesAutomatically = true
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                safeBrowsingEnabled = true
                mediaPlaybackRequiresUserGesture = true
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    pageProgress.progress = newProgress
                    pageProgress.visibility =
                        if (newProgress in 1..99) View.VISIBLE else View.GONE
                }

                override fun onReceivedTitle(view: WebView, title: String?) {
                    Log.d(TAG, "PAGE_TITLE title=${title.orEmpty()}")
                }

                override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                    Log.d(
                        TAG,
                        "WEB_CONSOLE level=${message.messageLevel()} " +
                            "line=${message.lineNumber()} message=${message.message()}"
                    )
                    return true
                }
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(
                    view: WebView,
                    url: String,
                    favicon: android.graphics.Bitmap?
                ) {
                    rendererErrorPanel.visibility = View.GONE
                    pageProgress.visibility = View.VISIBLE
                    pageProgress.progress = 0
                    if (!searchField.hasFocus()) searchField.setText(url)
                    Log.i(TAG, "PAGE_STARTED url=${url}")
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (!searchField.hasFocus()) searchField.setText(url)
                    pageProgress.progress = 100
                    pageProgress.visibility = View.GONE

                    view.evaluateJavascript(
                        "(function(){return JSON.stringify({" +
                            "title:document.title," +
                            "readyState:document.readyState," +
                            "bodyText:(document.body&&document.body.innerText||'').slice(0,180)" +
                            "})})()"
                    ) { result ->
                        Log.i(TAG, "PAGE_DOM_READY url=${url} dom=${result}")
                    }

                    // onPageFinished alone does not promise that the frame is visible.
                    // Wait for WebView's visual-state callback before declaring success.
                    view.postVisualStateCallback(
                        SystemClock.uptimeMillis(),
                        object : WebView.VisualStateCallback() {
                            override fun onComplete(requestId: Long) {
                                Log.i(TAG, "PAGE_VISUAL_STATE_READY url=${url}")
                                if (url.startsWith("http://") || url.startsWith("https://")) {
                                    lastSuccessfulUrl = url
                                }
                                if (rendererRecoveryPending) {
                                    rendererRecoveryPending = false
                                    Log.i(TAG, "RENDERER_RECOVERY_COMPLETED url=${url}")
                                }
                                scheduleRendererCrashTest(view, url)
                            }
                        }
                    )
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    if (request.isForMainFrame) {
                        Log.e(
                            TAG,
                            "MAIN_FRAME_ERROR code=${error.errorCode} " +
                                "description=${error.description} url=${request.url}"
                        )
                        pageProgress.visibility = View.GONE
                        showPageError("This page could not be loaded (${error.errorCode}).")
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: android.webkit.WebResourceResponse
                ) {
                    if (request.isForMainFrame) {
                        Log.w(
                            TAG,
                            "MAIN_FRAME_HTTP_ERROR status=${errorResponse.statusCode} " +
                                "url=${request.url}"
                        )
                    }
                }

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    if (adBlockingEnabled && AdBlocker.shouldBlock(request.url)) {
                        Log.d(TAG, "AD_REQUEST_BLOCKED host=${request.url.host.orEmpty()}")
                        return WebResourceResponse(
                            "text/plain",
                            "utf-8",
                            204,
                            "No Content",
                            emptyMap(),
                            java.io.ByteArrayInputStream(ByteArray(0))
                        )
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onRenderProcessGone(
                    view: WebView,
                    detail: RenderProcessGoneDetail
                ): Boolean {
                    Log.e(
                        TAG,
                        "RENDERER_GONE didCrash=${detail.didCrash()} " +
                            "priority=${detail.rendererPriorityAtExit()} url=${view.url.orEmpty()}"
                    )
                    recoverRenderer(view)
                    return true
                }
            }

            setDownloadListener(
                DownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
                    enqueueDownload(url, userAgent, contentDisposition, mimeType, contentLength)
                }
            )
        }
    }

    private fun scheduleRendererCrashTest(view: WebView, url: String) {
        if (!testCrashRenderer || testCrashTriggered || !url.startsWith("https://example.com")) {
            return
        }
        testCrashTriggered = true
        view.postDelayed({
            if (!isFinishing && !isDestroyed && ::browserView.isInitialized && browserView === view) {
                Log.i(TAG, "TEST_TRIGGER_RENDERER_CRASH url=${url}")
                view.loadUrl("chrome://crash")
            }
        }, 2500L)
    }

    private fun recoverRenderer(deadView: WebView) {
        val restoreUrl = lastSuccessfulUrl
            .takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: "about:blank"

        if (deadView.parent is ViewGroup) {
            (deadView.parent as ViewGroup).removeView(deadView)
        }
        // After onRenderProcessGone the old WebView must not be used again.
        // Android requires detaching it and destroying the instance.
        runCatching {
            deadView.destroy()
        }.onFailure {
            Log.w(TAG, "Dead WebView cleanup reported an error", it)
        }

        rendererRecoveryPending = true
        rendererErrorPanel.visibility = View.GONE
        browserView = createBrowserView()
        browserSlot.addView(
            browserView,
            0,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        browserView.loadUrl(restoreUrl)
        Log.i(TAG, "RENDERER_RECOVERY_STARTED restoreUrl=${restoreUrl}")
    }

    private fun buildLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        setContentView(root)

        val content = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        root.addView(
            content,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        browserSlot = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        content.addView(
            browserSlot,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        pageProgress = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = 0
            visibility = View.GONE
            progressTintList = ColorStateList.valueOf(Color.rgb(41, 217, 242))
            progressBackgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        }
        browserSlot.addView(
            pageProgress,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(2),
                Gravity.TOP
            )
        )

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
        content.addView(
            downloadsPanel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        rendererErrorMessage = textView(
            "This page could not be displayed. Reload to try again.",
            13f,
            Color.rgb(180, 180, 180)
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(9), 0, dp(20))
        }
        rendererErrorPanel = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
            val message = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(28), dp(24), dp(28), dp(24))
                addView(
                    textView("Page stopped unexpectedly", 18f, Color.WHITE, bold = true)
                )
                addView(rendererErrorMessage)
                addView(
                    textView("RELOAD PAGE", 12f, Color.WHITE, bold = true).apply {
                        gravity = Gravity.CENTER
                        setPadding(dp(20), dp(12), dp(20), dp(12))
                        background = roundedBackground(Color.rgb(38, 38, 38), dp(12))
                        setOnClickListener { retryBrowserPage() }
                    }
                )
            }
            addView(
                message,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }
        content.addView(
            rendererErrorPanel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

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
            setOnEditorActionListener { _, actionId, event ->
                val enter = event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER
                if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                    navigateFromAddressBar()
                    true
                } else {
                    false
                }
            }
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus && showingDownloads) showBrowser()
            }
        }
        searchShell.addView(
            searchField,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        bar.addView(searchShell, LinearLayout.LayoutParams(0, dp(54), 1f))

        shieldButton = createIconButton(R.drawable.ic_shield, "Toggle ad blocker") {
            toggleAdBlocking()
        }
        bar.addView(
            shieldButton,
            LinearLayout.LayoutParams(dp(48), dp(54)).apply {
                marginStart = dp(8)
            }
        )

        val downloadsButton = createIconButton(
            R.drawable.ic_download_arrow,
            "Open downloads",
            transparentBackground = true
        ) {
            toggleDownloads()
        }
        bar.addView(
            downloadsButton,
            LinearLayout.LayoutParams(dp(48), dp(54)).apply {
                marginStart = dp(8)
            }
        )
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
                ColorDrawable(Color.TRANSPARENT)
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
        rendererErrorPanel.visibility = View.GONE
        browserView.loadUrl(target)
        searchField.setText(target)
        searchField.clearFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(searchField.windowToken, 0)
    }

    private fun retryBrowserPage() {
        rendererErrorPanel.visibility = View.GONE
        browserView.reload()
    }

    private fun showPageError(message: String) {
        rendererErrorMessage.text = message
        rendererErrorPanel.visibility = View.VISIBLE
    }

    private fun toggleAdBlocking() {
        adBlockingEnabled = !adBlockingEnabled
        preferences.edit().putBoolean(KEY_AD_BLOCK, adBlockingEnabled).apply()
        updateShieldButton()
        Toast.makeText(
            this,
            if (adBlockingEnabled) "Ad blocking enabled" else "Ad blocking disabled",
            Toast.LENGTH_SHORT
        ).show()
        browserView.reload()
    }

    private fun updateShieldButton() {
        if (!::shieldButton.isInitialized) return
        shieldButton.alpha = if (adBlockingEnabled) 1f else 0.45f
        shieldButton.contentDescription =
            if (adBlockingEnabled) "Ad blocker on" else "Ad blocker off"
    }

    private fun toggleDownloads() {
        if (showingDownloads) {
            showBrowser()
        } else {
            showingDownloads = true
            browserSlot.visibility = View.GONE
            downloadsPanel.visibility = View.VISIBLE
            searchField.clearFocus()
            refreshDownloadRecords()
            renderDownloads()
            downloadHandler.removeCallbacks(downloadRefresh)
            downloadHandler.post(downloadRefresh)
        }
    }

    private fun showBrowser() {
        showingDownloads = false
        downloadHandler.removeCallbacks(downloadRefresh)
        downloadsPanel.visibility = View.GONE
        browserSlot.visibility = View.VISIBLE
    }

    private fun enqueueDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long
    ) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Toast.makeText(this, "This download link is not supported.", Toast.LENGTH_LONG).show()
            return
        }

        runCatching {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                .substringAfterLast('/')
                .ifBlank { "download" }
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(fileName)
                setDescription("Downloading in Browser")
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                setDestinationInExternalFilesDir(
                    this@MainActivity,
                    Environment.DIRECTORY_DOWNLOADS,
                    fileName
                )
                if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
                if (!userAgent.isNullOrBlank()) addRequestHeader("User-Agent", userAgent)
                CookieManager.getInstance().getCookie(url)?.let {
                    if (it.isNotBlank()) addRequestHeader("Cookie", it)
                }
            }

            val id = downloadManager.enqueue(request)
            downloadRecords[id] = DownloadRecord(
                id = id,
                name = fileName,
                url = url,
                path = "",
                totalBytes = contentLength.coerceAtLeast(0L),
                receivedBytes = 0L,
                percent = 0,
                complete = false,
                paused = false,
                canceled = false,
                interrupted = false,
                updatedAt = System.currentTimeMillis()
            )
            persistDownloads()
            Toast.makeText(this, "Download started: $fileName", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Log.e(TAG, "Could not enqueue download", it)
            Toast.makeText(this, "Could not start this download.", Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshDownloadRecords() {
        downloadRecords.values.forEach { record ->
            val cursor = downloadManager.query(
                DownloadManager.Query().setFilterById(record.id)
            ) ?: return@forEach

            cursor.use {
                if (!it.moveToFirst()) return@use
                val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                record.receivedBytes = it.getLong(
                    it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                ).coerceAtLeast(0L)
                record.totalBytes = it.getLong(
                    it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                ).coerceAtLeast(0L)
                val localUri = it.getString(
                    it.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)
                )
                if (!localUri.isNullOrBlank()) record.path = localUri
                record.percent = if (record.totalBytes > 0L) {
                    ((record.receivedBytes * 100L) / record.totalBytes).toInt().coerceIn(0, 100)
                } else {
                    0
                }
                record.complete = status == DownloadManager.STATUS_SUCCESSFUL
                record.paused = status == DownloadManager.STATUS_PAUSED
                record.interrupted = status == DownloadManager.STATUS_FAILED
                record.updatedAt = System.currentTimeMillis()
            }
        }
        persistDownloads()
    }

    private fun cancelDownload(record: DownloadRecord) {
        downloadManager.remove(record.id)
        record.canceled = true
        record.paused = false
        record.interrupted = false
        record.updatedAt = System.currentTimeMillis()
        persistDownloads()
        renderDownloads()
    }

    private fun renderDownloads() {
        if (!::downloadsList.isInitialized) return
        downloadsList.removeAllViews()
        downloadsList.addView(textView("Downloads", 24f, Color.WHITE, bold = true))
        downloadsList.addView(
            textView(
                "Files downloaded in this browser",
                13f,
                Color.rgb(155, 155, 155)
            ).apply {
                setPadding(0, dp(7), 0, dp(25))
            }
        )

        if (downloadRecords.isEmpty()) {
            downloadsList.addView(
                textView("No downloads yet", 14f, Color.rgb(180, 180, 180)).apply {
                    setPadding(0, dp(24), 0, 0)
                }
            )
            return
        }

        downloadRecords.values.sortedByDescending { it.updatedAt }.forEach {
            downloadsList.addView(createDownloadCard(it))
        }
    }

    private fun createDownloadCard(record: DownloadRecord): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(12))
            background = roundedBackground(Color.rgb(16, 16, 16), dp(14))
        }

        val active = !record.complete && !record.canceled && !record.interrupted
        val statusText = when {
            record.complete -> "DONE"
            record.canceled -> "CANCELED"
            record.interrupted -> "FAILED"
            record.paused -> "PAUSED"
            else -> "${record.percent}%"
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(
            textView(record.name, 14f, Color.WHITE, bold = true).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        titleRow.addView(
            textView(statusText, 10f, Color.rgb(190, 190, 190), bold = true)
        )
        card.addView(titleRow)

        val detail = when {
            record.complete -> "Completed · ${formatBytes(record.receivedBytes)}"
            record.canceled -> "Canceled"
            record.interrupted -> "Download failed"
            record.paused -> "Paused · ${formatBytes(record.receivedBytes)} received"
            else -> "${formatBytes(record.receivedBytes)} of ${formatBytes(record.totalBytes)}"
        }
        card.addView(
            textView(detail, 12f, Color.rgb(165, 165, 165)).apply {
                setPadding(0, dp(8), 0, dp(10))
            }
        )

        if (active) {
            val progress = ProgressBar(
                this,
                null,
                android.R.attr.progressBarStyleHorizontal
            ).apply {
                max = 100
                this.progress = record.percent
                progressTintList = ColorStateList.valueOf(Color.WHITE)
                progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(58, 58, 58))
            }
            card.addView(
                progress,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(4)
                )
            )
            card.addView(
                textView("CANCEL", 11f, Color.WHITE, bold = true).apply {
                    gravity = Gravity.END
                    setPadding(dp(12), dp(10), dp(2), dp(2))
                    setOnClickListener { cancelDownload(record) }
                }
            )
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
            val uri = downloadManager.getUriForDownloadedFile(record.id)
                ?: throw IllegalStateException("Download file is not available")
            val extension = record.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val mime = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(extension) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        }.onFailure {
            Log.w(TAG, "Could not open downloaded file ${record.name}", it)
            Toast.makeText(this, "No installed app can open this file.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun persistDownloads() {
        val array = JSONArray()
        downloadRecords.values.forEach { record ->
            array.put(
                JSONObject().apply {
                    put("id", record.id)
                    put("name", record.name)
                    put("url", record.url)
                    put("path", record.path)
                    put("total", record.totalBytes)
                    put("received", record.receivedBytes)
                    put("percent", record.percent)
                    put("complete", record.complete)
                    put("paused", record.paused)
                    put("canceled", record.canceled)
                    put("interrupted", record.interrupted)
                    put("updated", record.updatedAt)
                }
            )
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
                    id = item.optLong("id"),
                    name = item.optString("name", "Download"),
                    url = item.optString("url"),
                    path = item.optString("path"),
                    totalBytes = item.optLong("total"),
                    receivedBytes = item.optLong("received"),
                    percent = item.optInt("percent").coerceIn(0, 100),
                    complete = complete,
                    paused = item.optBoolean("paused"),
                    canceled = item.optBoolean("canceled"),
                    interrupted = item.optBoolean("interrupted") || !complete,
                    updatedAt = item.optLong("updated")
                )
                downloadRecords[record.id] = record
            }
        }.onFailure {
            Log.w(TAG, "Could not restore saved download records", it)
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

    private fun roundedBackground(fill: Int, radius: Int) =
        GradientDrawable().apply {
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

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    @Deprecated("Use the activity back dispatcher")
    override fun onBackPressed() {
        when {
            showingDownloads -> showBrowser()
            ::browserView.isInitialized && browserView.canGoBack() -> browserView.goBack()
            else -> super.onBackPressed()
        }
    }

    override fun onPause() {
        if (::browserView.isInitialized) browserView.onPause()
        downloadHandler.removeCallbacks(downloadRefresh)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::browserView.isInitialized) browserView.onResume()
        if (showingDownloads) downloadHandler.post(downloadRefresh)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::browserView.isInitialized) browserView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        downloadHandler.removeCallbacksAndMessages(null)
        if (::browserView.isInitialized) {
            (browserView.parent as? ViewGroup)?.removeView(browserView)
            runCatching {
                browserView.stopLoading()
                browserView.webChromeClient = null
                browserView.webViewClient = WebViewClient()
                browserView.destroy()
            }.onFailure {
                Log.w(TAG, "WebView cleanup reported an error", it)
            }
        }
        super.onDestroy()
    }

    private companion object {
        const val PREFERENCES = "browser_preferences"
        const val KEY_AD_BLOCK = "ad_block_enabled"
        const val KEY_DOWNLOADS = "downloads_json"
        const val TAG = "Browser"
        const val DOWNLOAD_REFRESH_MS = 1000L
        const val EXTRA_TEST_URL = "com.search.browser.TEST_URL"
        const val EXTRA_TEST_CRASH_RENDERER = "com.search.browser.TEST_CRASH_RENDERER"
    }
}

private object AdBlocker {
    private val blockedHosts = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.com",
        "adnxs.com",
        "adsystem.com",
        "adform.net",
        "taboola.com",
        "outbrain.com",
        "scorecardresearch.com",
        "criteo.com",
        "serving-sys.com"
    )

    fun shouldBlock(uri: Uri): Boolean {
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        return blockedHosts.any { host == it || host.endsWith("$.$it") }
    }
}
