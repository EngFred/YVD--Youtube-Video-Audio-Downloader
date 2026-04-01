package com.engfred.yvd

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.engfred.yvd.data.network.DownloaderImpl
import dagger.hilt.android.HiltAndroidApp
import org.schabi.newpipe.extractor.NewPipe
import javax.inject.Inject

/**
 * Application entry point.
 *
 * **Single responsibility here:** initialize app-wide singletons and Android system channels.
 * All heavy dependencies are provided by Hilt — we never call `new X()` in this class.
 *
 * ## NewPipe initialization contract
 * `NewPipe.init()` is called **exactly once**, here, with the **injected** [DownloaderImpl]
 * singleton. This guarantees that:
 * 1. The same [OkHttpClient] (and its connection pool) is used for both metadata extraction
 *    and file downloads.
 * 2. No component downstream (Repository, Worker, etc.) ever calls `NewPipe.init()` again,
 *    which would silently swap out the Downloader and break the shared connection pool.
 *
 * Hilt field injection is performed during [attachBaseContext], so all `@Inject` fields
 * are guaranteed to be non-null by the time [onCreate] runs.
 */
@HiltAndroidApp
class YVDApplication : Application(), Configuration.Provider {

    private val TAG = "YVD_APP"

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    /**
     * The same singleton that is injected into the Repository and used for file downloads.
     * We inject it here solely to call [NewPipe.init] with it once.
     */
    @Inject
    lateinit var downloaderImpl: DownloaderImpl

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Application starting...")

        // ── Single initialization point for NewPipe ──────────────────────────
        // Do NOT call NewPipe.init() anywhere else in the codebase.
        NewPipe.init(downloaderImpl)
        Log.d(TAG, "NewPipe initialized with shared DownloaderImpl singleton")

        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Low-importance channel for ongoing download progress (no sound/vibration)
            val progressChannel = NotificationChannel(
                "download_channel",
                "Download Progress",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress of active video/audio downloads"
            }

            // High-importance channel for completion / failure (vibrates, heads-up)
            val completionChannel = NotificationChannel(
                "download_completed",
                "Download Completed",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies when a download finishes or fails"
                enableVibration(true)
            }

            nm.createNotificationChannel(progressChannel)
            nm.createNotificationChannel(completionChannel)
        }
    }
}