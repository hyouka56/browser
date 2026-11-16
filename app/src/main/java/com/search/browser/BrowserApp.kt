package com.search.browser

import android.app.Application
import android.util.Log
import com.cefrium.Cefrium

class BrowserApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Android creates the Application instance in every app process,
        // including Chromium's isolated sandboxed renderer processes.
        // Cefrium's EasyList initialization must only run in the main process.
        val processName = Application.getProcessName()
        if (!isMainApplicationProcess(processName, packageName)) {
            Log.i(TAG, "Skipping EasyList initialization in non-main process: $processName")
            return
        }

        Log.i(TAG, "Initializing EasyList in main process: $processName")
        // EasyList is downloaded and cached by Cefrium. A failed refresh keeps
        // the last known-good list, when one exists.
        runCatching {
            Cefrium.downloadAndLoadEasyList(this) { ruleCount, error ->
                if (error != null) Log.w(TAG, "EasyList refresh failed: $error")
                else Log.i(TAG, "EasyList loaded: $ruleCount rules")
                // YouTube's ad endpoints are covered by separate Cefrium heuristics.
                runCatching {
                    Cefrium.loadYouTubeAdRules()
                    Log.i(TAG, "YouTube ad rules loaded")
                }.onFailure { Log.w(TAG, "Could not load YouTube ad rules", it) }
            }
        }.onFailure { Log.e(TAG, "Could not start EasyList", it) }
    }

    private companion object {
        const val TAG = "BrowserApp"
    }
}

internal fun isMainApplicationProcess(processName: String, packageName: String): Boolean =
    processName == packageName
