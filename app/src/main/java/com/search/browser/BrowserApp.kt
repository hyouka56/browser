package com.search.browser

import android.app.Application
import android.util.Log
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

class BrowserApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val contentBlocking = ContentBlocking.Settings.Builder()
            .antiTracking(ContentBlocking.AntiTracking.DEFAULT)
            .build()
        runtime = GeckoRuntime.create(
            this,
            GeckoRuntimeSettings.Builder()
                .contentBlocking(contentBlocking)
                .consoleOutput(BuildConfig.DEBUG)
                .remoteDebuggingEnabled(BuildConfig.DEBUG)
                .build()
        )
        Log.i("BrowserApp", "GeckoView stable runtime initialized: $GECKOVIEW_VERSION")
    }

    companion object {
        const val GECKOVIEW_VERSION = "160.0.20261104181552"
        lateinit var runtime: GeckoRuntime
            private set
    }
}
