package com.search.browser

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.cefrium.CefriumBrowser

/** Keeps the Cefrium process at foreground priority while a user-started download is active. */
class DownloadKeepAliveService : Service() {
    override fun onCreate() {
        super.onCreate()
        running = true
        createNotificationChannel()
        Log.i(TAG, "DOWNLOAD_KEEP_ALIVE_SERVICE_CREATED")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = true
        val name = intent?.getStringExtra(EXTRA_DOWNLOAD_NAME)
            ?.takeIf { it.isNotBlank() } ?: "Active download"
        val openDownloads = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_SHOW_DOWNLOADS, true)
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_DOWNLOADS,
            openDownloads,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download_arrow)
            .setContentTitle("Download in progress")
            .setContentText(name)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        Log.i(TAG, "DOWNLOAD_KEEP_ALIVE_SERVICE_STARTED name=$name")
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // android:stopWithTask=false keeps this service alive when the user swipes
        // Browser out of Recents. A real force-stop is still controlled by Android.
        Log.i(TAG, "DOWNLOAD_KEEP_ALIVE_TASK_REMOVED")
        super.onTaskRemoved(rootIntent)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "DOWNLOAD_KEEP_ALIVE_FOREGROUND_TIMEOUT type=$fgsType")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        running = false
        retainedBrowser = null
        retainedActivity = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.i(TAG, "DOWNLOAD_KEEP_ALIVE_SERVICE_STOPPED")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Background downloads",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps Browser downloads running when its screen is closed"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "Browser"
        private const val CHANNEL_ID = "browser_download_keep_alive"
        private const val NOTIFICATION_ID = 4107
        private const val REQUEST_OPEN_DOWNLOADS = 4108
        private const val EXTRA_DOWNLOAD_NAME = "com.search.browser.DOWNLOAD_NAME"
        private const val EXTRA_SHOW_DOWNLOADS = "com.search.browser.SHOW_DOWNLOADS"

        @Volatile
        private var running = false

        // A browser's download callback is owned by its Activity. Keep both
        // strongly reachable while the foreground service protects the transfer.
        @Volatile
        private var retainedActivity: MainActivity? = null

        @Volatile
        private var retainedBrowser: CefriumBrowser? = null

        val isRunning: Boolean
            get() = running

        fun retainBrowser(activity: MainActivity, browser: CefriumBrowser) {
            retainedActivity = activity
            retainedBrowser = browser
        }

        fun releaseBrowser(activity: MainActivity) {
            if (retainedActivity === activity) {
                retainedActivity = null
                retainedBrowser = null
            }
        }

        fun start(context: Context, name: String): Boolean {
            val intent = Intent(context, DownloadKeepAliveService::class.java).apply {
                putExtra(EXTRA_DOWNLOAD_NAME, name)
            }
            return ContextCompat.startForegroundService(context, intent) != null
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DownloadKeepAliveService::class.java))
        }
    }
}
