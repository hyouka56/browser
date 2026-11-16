package com.search.browser

import com.cefrium.CefriumBrowser

data class DownloadRecord(
    val id: Int,
    var name: String,
    var url: String,
    var path: String,
    var totalBytes: Long,
    var receivedBytes: Long,
    var speedBytesPerSecond: Long,
    var percent: Int,
    var complete: Boolean,
    var paused: Boolean,
    var canceled: Boolean,
    var interrupted: Boolean,
    var updatedAt: Long,
    var mimeType: String = "application/octet-stream",
    @Transient var liveItem: CefriumBrowser.DownloadItem? = null
)
