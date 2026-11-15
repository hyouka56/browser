package com.search.browser

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.URLUtil
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class MainActivity : AppCompatActivity() {

    private lateinit var geckoView: GeckoView
    private lateinit var geckoSession: GeckoSession
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
    private var currentPageUrl = "about:blank"
    private var sessionRecoveryPending = false
    private var sessionRecoveryAttempts = 0
    private var testSessionRecovery = false
    private var testSessionRecoveryTriggered = false
    private var canGoBack = false

    private val preferences by lazy {
        getSharedPreferences(PREFERENCES, MODE_PRIVATE)
    }
    private val downloadRecords = LinkedHashMap<Long, DownloadRecord>()
    private val downloadIdGenerator = AtomicLong(System.currentTimeMillis())
    private val downloadExecutor = Executors.newSingleThreadExecutor()
    private val activeDownloads = ConcurrentHashMap<Long, ActiveDownload>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        adBlockingEnabled = preferences.getBoolean(KEY_AD_BLOCK, true)
        lastSuccessfulUrl = preferences.getString(KEY_LAST_URL, "about:blank").orEmpty()
            .ifBlank { "about:blank" }
        currentPageUrl = lastSuccessfulUrl
        restoreSavedDownloads()
        buildLayout()
        BrowserApp.runtime.settings.contentBlocking.setAntiTracking(
            if (adBlockingEnabled) ContentBlocking.AntiTracking.DEFAULT
            else ContentBlocking.AntiTracking.NONE
        )

        Log.i(
            TAG,
            "GECKOVIEW_ENGINE version=${BrowserApp.GECKOVIEW_VERSION} " +
                "supportedAbis=${android.os.Build.SUPPORTED_ABIS.joinToString()}"
        )

        testSessionRecovery = BuildConfig.DEBUG &&
            intent.getBooleanExtra(EXTRA_TEST_RECOVER_SESSION, false)

        val testUrl = if (BuildConfig.DEBUG) {
            intent.getStringExtra(EXTRA_TEST_URL)?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        val initialUrl = testUrl ?: lastSuccessfulUrl

        geckoSession = createGeckoSession()
        geckoSession.open(BrowserApp.runtime)
        geckoView.setSession(geckoSession)
        geckoSession.loadUri(initialUrl)

        updateShieldButton()
        renderDownloads()
    }

    private fun createGeckoSession(): GeckoSession {
        return GeckoSession(
            GeckoSessionSettings.Builder()
                .useTrackingProtection(adBlockingEnabled)
                .build()
        ).apply {
            setContentDelegate(object : GeckoSession.ContentDelegate {
                override fun onTitleChange(session: GeckoSession, title: String?) {
                    Log.i(TAG, "PAGE_TITLE title=${title.orEmpty()}")
                }

                override fun onFirstContentfulPaint(session: GeckoSession) {
                    val url = currentPageUrl
                    Log.i(TAG, "PAGE_FIRST_CONTENTFUL_PAINT url=$url")
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        lastSuccessfulUrl = url
                        preferences.edit().putString(KEY_LAST_URL, url).apply()
                    }
                    if (sessionRecoveryPending) {
                        sessionRecoveryPending = false
                        sessionRecoveryAttempts = 0
                        Log.i(TAG, "SESSION_RECOVERY_COMPLETED url=$url")
                    }
                    scheduleSessionRecoveryTest(session, url)
                }

                override fun onCrash(session: GeckoSession) {
                    Log.e(TAG, "GECKO_CONTENT_PROCESS_CRASHED url=$currentPageUrl")
                    runOnUiThread { recoverSession(session, "content-process-crash") }
                }

                override fun onKill(session: GeckoSession) {
                    Log.e(TAG, "GECKO_CONTENT_PROCESS_KILLED url=$currentPageUrl")
                    runOnUiThread { recoverSession(session, "content-process-killed") }
                }

                override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                    handleExternalResponse(response)
                }
            })

            setProgressDelegate(object : GeckoSession.ProgressDelegate {
                override fun onPageStart(session: GeckoSession, url: String) {
                    currentPageUrl = url
                    runOnUiThread {
                        rendererErrorPanel.visibility = View.GONE
                        pageProgress.visibility = View.VISIBLE
                        pageProgress.progress = 0
                        if (!searchField.hasFocus()) searchField.setText(url)
                    }
                    Log.i(TAG, "PAGE_STARTED url=$url")
                }

                override fun onPageStop(session: GeckoSession, success: Boolean) {
                    runOnUiThread {
                        pageProgress.progress = if (success) 100 else 0
                        pageProgress.visibility = View.GONE
                        if (!success && !isFinishing && !isDestroyed) {
                            showPageError("This page could not be loaded. Check the address or connection.")
                        }
                    }
                    Log.i(TAG, "PAGE_LOAD_COMPLETE url=$currentPageUrl success=$success")
                }

                override fun onProgressChange(session: GeckoSession, progress: Int) {
                    runOnUiThread {
                        pageProgress.progress = progress.coerceIn(0, 100)
                        pageProgress.visibility = if (progress in 1..99) View.VISIBLE else View.GONE
                    }
                }
            })

            setNavigationDelegate(object : GeckoSession.NavigationDelegate {
                override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                    this@MainActivity.canGoBack = canGoBack
                }
            })
        }
    }

    private fun scheduleSessionRecoveryTest(session: GeckoSession, url: String) {
        if (!testSessionRecovery || testSessionRecoveryTriggered ||
            !url.startsWith("https://example.com")
        ) return

        testSessionRecoveryTriggered = true
        geckoView.postDelayed({
            if (!isFinishing && !isDestroyed &&
                ::geckoSession.isInitialized && geckoSession === session
            ) {
                Log.i(TAG, "TEST_TRIGGER_SESSION_RECOVERY url=$url")
                recoverSession(session, "emulator-test")
            }
        }, 5000L)
    }

    private fun recoverSession(failedSession: GeckoSession, reason: String) {
        if (isFinishing || isDestroyed || showingDownloads ||
            !::geckoSession.isInitialized || geckoSession !== failedSession
        ) return

        if (sessionRecoveryAttempts >= 2) {
            showPageError("The page stopped repeatedly. Reload to try again.")
            return
        }
        sessionRecoveryAttempts++

        val restoreUrl = lastSuccessfulUrl
            .takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: currentPageUrl.takeIf {
                it.startsWith("http://") || it.startsWith("https://")
            }
            ?: "about:blank"

        sessionRecoveryPending = true
        rendererErrorPanel.visibility = View.GONE
        pageProgress.visibility = View.VISIBLE
        val detachedSession = geckoView.releaseSession()
        if (detachedSession?.isOpen == true) {
            runCatching { detachedSession.close() }
                .onFailure { Log.w(TAG, "Failed to close the detached GeckoSession", it) }
        }

        val replacement = createGeckoSession()
        geckoSession = replacement
        replacement.open(BrowserApp.runtime)
        geckoView.setSession(replacement)
        replacement.loadUri(restoreUrl)
        Log.i(TAG, "SESSION_RECOVERY_STARTED reason=$reason restoreUrl=$restoreUrl")
    }

    private fun handleExternalResponse(response: WebResponse) {
        if (isFinishing || isDestroyed) {
            runCatching { response.body?.close() }
            return
        }
        val body = response.body
        if (body == null) {
            Log.w(TAG, "DOWNLOAD_RESPONSE_WITHOUT_BODY uri=${response.uri}")
            return
        }

        val disposition = response.headers.entries
            .firstOrNull { it.key.equals("content-disposition", ignoreCase = true) }
            ?.value
        val mimeType = response.headers.entries
            .firstOrNull { it.key.equals("content-type", ignoreCase = true) }
            ?.value
            ?.substringBefore(';')
        val guessedName = URLUtil.guessFileName(response.uri, disposition, mimeType)
        val fileName = guessedName
            .substringAfterLast('/')
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .take(180)
            .ifBlank { "download" }
        val downloadDirectory = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
        if (!downloadDirectory.exists() && !downloadDirectory.mkdirs()) {
            runCatching { body.close() }
            Toast.makeText(this, "Could not create the downloads folder.", Toast.LENGTH_LONG).show()
            return
        }

        val destination = File(downloadDirectory, fileName)
        val id = downloadIdGenerator.getAndIncrement()
        val totalBytes = response.headers.entries
            .firstOrNull { it.key.equals("content-length", ignoreCase = true) }
            ?.value
            ?.toLongOrNull()
            ?.coerceAtLeast(0L) ?: 0L
        val record = DownloadRecord(
            id = id,
            name = fileName,
            url = response.uri,
            path = destination.absolutePath,
            totalBytes = totalBytes,
            receivedBytes = 0L,
            percent = 0,
            complete = false,
            paused = false,
            canceled = false,
            interrupted = false,
            updatedAt = System.currentTimeMillis()
        )
        val task = ActiveDownload()
        activeDownloads[id] = task
        runOnUiThread {
            downloadRecords[id] = record
            persistDownloads()
            if (showingDownloads) renderDownloads()
            Toast.makeText(this, "Download started: $fileName", Toast.LENGTH_SHORT).show()
        }

        downloadExecutor.execute {
            var received = 0L
            var lastUiUpdate = 0L
            try {
                body.use { input ->
                    task.stream.set(input)
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (!task.cancelRequested.get()) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            received += count
                            val now = System.currentTimeMillis()
                            if (now - lastUiUpdate >= 400L) {
                                lastUiUpdate = now
                                val bytes = received
                                runOnUiThread {
                                    if (activeDownloads.containsKey(id)) {
                                        record.receivedBytes = bytes
                                        record.totalBytes = maxOf(record.totalBytes, bytes)
                                        record.percent = if (record.totalBytes > 0L) {
                                            ((bytes * 100L) / record.totalBytes).toInt().coerceIn(0, 99)
                                        } else 0
                                        record.updatedAt = now
                                        persistDownloads()
                                        if (showingDownloads) renderDownloads()
                                    }
                                }
                            }
                        }
                        output.flush()
                    }
                }

                if (task.cancelRequested.get()) {
                    destination.delete()
                } else {
                    runOnUiThread {
                        record.receivedBytes = received
                        record.totalBytes = maxOf(record.totalBytes, received)
                        record.percent = 100
                        record.complete = true
                        record.interrupted = false
                        record.updatedAt = System.currentTimeMillis()
                        persistDownloads()
                        if (showingDownloads) renderDownloads()
                        Toast.makeText(this, "Download complete: $fileName", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (error: Exception) {
                if (!task.cancelRequested.get()) {
                    Log.e(TAG, "Download failed: $fileName", error)
                    destination.delete()
                    runOnUiThread {
                        record.interrupted = true
                        record.complete = false
                        record.updatedAt = System.currentTimeMillis()
                        persistDownloads()
                        if (showingDownloads) renderDownloads()
                        Toast.makeText(this, "Download failed: $fileName", Toast.LENGTH_LONG).show()
                    }
                }
            } finally {
                task.stream.set(null)
                activeDownloads.remove(id)
            }
        }
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

        geckoView = GeckoView(this).apply {
            setBackgroundColor(Color.BLACK)
            coverUntilFirstPaint(Color.BLACK)
            setAutofillEnabled(true)
        }
        browserSlot.addView(
            geckoView,
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

        shieldButton = createIconButton(R.drawable.ic_shield, "Toggle tracking protection") {
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
        sessionRecoveryAttempts = 0
        geckoSession.loadUri(target)
        searchField.setText(target)
        searchField.clearFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(searchField.windowToken, 0)
    }

    private fun retryBrowserPage() {
        rendererErrorPanel.visibility = View.GONE
        sessionRecoveryAttempts = 0
        geckoSession.reload()
    }

    private fun showPageError(message: String) {
        rendererErrorMessage.text = message
        rendererErrorPanel.visibility = View.VISIBLE
    }

    private fun toggleAdBlocking() {
        adBlockingEnabled = !adBlockingEnabled
        preferences.edit().putBoolean(KEY_AD_BLOCK, adBlockingEnabled).apply()
        updateShieldButton()
        BrowserApp.runtime.settings.contentBlocking.setAntiTracking(
            if (adBlockingEnabled) ContentBlocking.AntiTracking.DEFAULT
            else ContentBlocking.AntiTracking.NONE
        )
        geckoSession.settings.setUseTrackingProtection(adBlockingEnabled)
        Toast.makeText(
            this,
            if (adBlockingEnabled) "Tracking protection enabled" else "Tracking protection disabled",
            Toast.LENGTH_SHORT
        ).show()
        geckoSession.reload()
    }

    private fun updateShieldButton() {
        if (!::shieldButton.isInitialized) return
        shieldButton.alpha = if (adBlockingEnabled) 1f else 0.45f
        shieldButton.contentDescription =
            if (adBlockingEnabled) "Tracking protection on" else "Tracking protection off"
    }

    private fun toggleDownloads() {
        if (showingDownloads) {
            showBrowser()
        } else {
            showingDownloads = true
            browserSlot.visibility = View.GONE
            downloadsPanel.visibility = View.VISIBLE
            searchField.clearFocus()
            renderDownloads()
        }
    }

    private fun showBrowser() {
        showingDownloads = false
        downloadsPanel.visibility = View.GONE
        browserSlot.visibility = View.VISIBLE
    }

    private fun cancelDownload(record: DownloadRecord) {
        activeDownloads[record.id]?.let { task ->
            task.cancelRequested.set(true)
            runCatching { task.stream.getAndSet(null)?.close() }
                .onFailure { Log.w(TAG, "Failed to close canceled download stream", it) }
        }
        File(record.path).delete()
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
        val file = File(record.path)
        if (!file.exists()) {
            Toast.makeText(this, "Downloaded file is not available.", Toast.LENGTH_LONG).show()
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
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
            ::geckoSession.isInitialized && geckoSession.isOpen && canGoBack -> geckoSession.goBack()
            else -> super.onBackPressed()
        }
    }

    override fun onPause() {
        if (::geckoSession.isInitialized && geckoSession.isOpen) geckoSession.setActive(false)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::geckoSession.isInitialized && geckoSession.isOpen) geckoSession.setActive(true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (currentPageUrl.startsWith("http://") || currentPageUrl.startsWith("https://")) {
            preferences.edit().putString(KEY_LAST_URL, currentPageUrl).apply()
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        activeDownloads.values.forEach { task ->
            task.cancelRequested.set(true)
            runCatching { task.stream.getAndSet(null)?.close() }
        }
        downloadExecutor.shutdownNow()
        if (::geckoView.isInitialized) {
            val oldSession = geckoView.releaseSession()
            if (oldSession?.isOpen == true) {
                runCatching { oldSession.close() }
                    .onFailure { Log.w(TAG, "GeckoSession cleanup reported an error", it) }
            }
        }
        super.onDestroy()
    }

    private companion object {
        const val PREFERENCES = "browser_preferences"
        const val KEY_AD_BLOCK = "ad_block_enabled"
        const val KEY_DOWNLOADS = "downloads_json"
        const val KEY_LAST_URL = "last_url"
        const val TAG = "Browser"
        const val EXTRA_TEST_URL = "com.search.browser.TEST_URL"
        const val EXTRA_TEST_RECOVER_SESSION = "com.search.browser.TEST_RECOVER_SESSION"
    }
}

private class ActiveDownload {
    val cancelRequested = AtomicBoolean(false)
    val stream = AtomicReference<InputStream?>(null)
}
