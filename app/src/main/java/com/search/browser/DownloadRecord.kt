package com.search.browser

data class DownloadRecord(
    val id: Long,
    var name: String,
    var url: String,
    var path: String,
    var totalBytes: Long,
    var receivedBytes: Long,
    var percent: Int,
    var complete: Boolean,
    var paused: Boolean,
    var canceled: Boolean,
    var interrupted: Boolean,
    var updatedAt: Long
)
