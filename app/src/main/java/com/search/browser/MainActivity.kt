package com.search.browser

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.cefrium.CefriumBrowser
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var browser: CefriumBrowser
    private lateinit var browserSlot: FrameLayout
    private lateinit var downloadsPanel: ScrollView
    private lateinit var downloadsList: LinearLayout
    private lateinit var searchField: EditText
    private lateinit var shieldButton: ImageButton

    private val preferences by lazy { getSharedPreferences(PREFERENCES, MODE_PRIVATE) }
    private val downloadRecords = LinkedHashMap<Int, DownloadRecord>()
    private val activeDownloadItems = LinkedHashMap<String, CefriumBrowser.DownloadItem>()
    private var adBlockingEnabled = true
    private var showingDownloads = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.decorView.systemUiVisibility = 0

        adBlockingEnabled = preferences.getBoolean(KEY_AD_BLOCK, true)
        restoreSavedDownloads()
        buildLayout()

        browser = CefriumBrowser.createWithSurface(this)
        browser.setBackgroundColor(Color.BLACK)
        browser.setAdBlockEnabled(adBlockingEnabled)
        browserSlot.addView(
            browser.getSurfaceContainer(),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        browser.setCallback(object : CefriumBrowser.Callback() {
            override fun onUrlChanged(url: String) {
                runOnUiThread {
                    if (!searchField.hasFocus()) searchField.setText(url)
                }
            }
        })

        // The rich handler provides a DownloadItem with live speed, byte counts,
        // and pause/resume/cancel controls. The legacy listener below supplies
        // the user-facing name, path and percent changes.
        browser.setDownloadHandler(object : CefriumBrowser.CefriumDownloadHandler {
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
                // null asks Cefrium to save to its app-managed Downloads directory.
                callback.allow(null)
            }
        })

        browser.setOnDownloadListener(object : CefriumBrowser.OnDownloadListener {
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

        val savedBrowserState = savedInstanceState?.getBundle(KEY_BROWSER_STATE)
        if (savedBrowserState != null) browser.restoreState(savedBrowserState)
        else browser.loadUrl("about:blank")

        updateShieldButton()
        renderDownloads()
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
        content.addView(browserSlot, FrameLayout.LayoutParams(
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

        val downloadsButton = createIconButton(R.drawable.ic_download_arrow, "Open downloads") {
            toggleDownloads()
        }
        bar.addView(downloadsButton, LinearLayout.LayoutParams(dp(48), dp(54)).apply {
            marginStart = dp(8)
        })
        return bar
    }

    private fun createIconButton(icon: Int, description: String, action: () -> Unit): ImageButton {
        return ImageButton(this).apply {
            setImageResource(icon)
            contentDescription = description
            scaleType = android.widget.ImageView.ScaleType.CENTER
            background = roundedBackground(Color.rgb(17, 17, 17), dp(15))
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            setOnClickListener { action() }
        }
    }

    private fun navigateFromAddressBar() {
        val target = BrowserAddressResolver.resolve(searchField.text?.toString().orEmpty())
        if (target.isBlank()) return

        showBrowser()
        browser.loadUrl(target)
        searchField.setText(target)
        searchField.clearFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(searchField.windowToken, 0)
    }

    private fun toggleAdBlocking() {
        adBlockingEnabled = !adBlockingEnabled
        preferences.edit().putBoolean(KEY_AD_BLOCK, adBlockingEnabled).apply()
        browser.setAdBlockEnabled(adBlockingEnabled)
        updateShieldButton()
        Toast.makeText(
            this,
            if (adBlockingEnabled) "Ad blocking enabled" else "Ad blocking disabled",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateShieldButton() {
        if (!::shieldButton.isInitialized) return
        shieldButton.alpha = if (adBlockingEnabled) 1f else 0.45f
        shieldButton.contentDescription = if (adBlockingEnabled) "Ad blocker on" else "Ad blocker off"
    }

    private fun toggleDownloads() {
        if (showingDownloads) showBrowser() else {
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

        persistDownloads()
        if (showingDownloads) renderDownloads()
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
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (::browser.isInitialized) browser.close()
        super.onDestroy()
    }

    private companion object {
        const val PREFERENCES = "browser_preferences"
        const val KEY_AD_BLOCK = "ad_block_enabled"
        const val KEY_DOWNLOADS = "downloads_json"
        const val KEY_BROWSER_STATE = "browser_state"
    }
}
