package com.search.browser

import android.app.Application
import android.util.Log

class BrowserApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i("BrowserApp", "Using the Android System WebView provider")
    }
}
