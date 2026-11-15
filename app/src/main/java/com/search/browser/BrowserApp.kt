package com.search.browser

import android.app.Application
import android.util.Log
import com.cefrium.Cefrium

class BrowserApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // EasyList is downloaded and cached by Cefrium. A failed refresh keeps
        // the last known-good list, when one exists.
        runCatching {
            Cefrium.downloadAndLoadEasyList(this) { ruleCount, error ->
                if (error != null) Log.w(TAG, "EasyList refresh failed: $error")
                else Log.i(TAG, "EasyList loaded: $ruleCount rules")
            }
        }.onFailure { Log.e(TAG, "Could not start EasyList", it) }
    }

    private companion object { const val TAG = "BrowserApp" }
}
