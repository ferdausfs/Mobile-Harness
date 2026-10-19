package com.jarves.mh.zweb

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.jarves.mh.MainActivity
import com.jarves.mh.R

/**
 * Foreground service that keeps the hidden-WebView Z.ai engine (and the chat
 * task loop) alive while the screen is off (Phase 3).
 *
 * Why `dataSync`: the engine's job is streaming generation data between the
 * app and chat.z.ai — exactly the case the documented `dataSync` type covers.
 * The CLI runtime keeps its own `specialUse` service (local process execution,
 * not data sync). dataSync comes with Android 14+'s 6-hour-per-24h limit, so
 * [ZaiForegroundPolicy.checkpointDeadlineMillis] makes the service checkpoint
 * (persist the task queue) and notify BEFORE the platform would cut it off —
 * a graceful step-down instead of a silent kill.
 *
 * The engine itself lives in the ViewModel-owned ZaiWebController (same
 * process); this service holds the foreground priority + partial wake lock
 * that keep that process and its WebView schedules alive, and mirrors the
 * engine's phase into a persistent notification with a working Stop action
 * ([ZaiEngineStopController] is the same static-hook pattern the CLI
 * RuntimeTaskController uses).
 */
object ZaiEngineStopController {
    @Volatile
    var stopAction: (() -> Unit)? = null

    fun requestStop() {
        stopAction?.invoke()
    }
}

class ZaiEngineService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var startedAtMillis: Long = 0L
    private var lastDetail: String = "Working on your task"
    private var checkpointed = false

    private val checkpointTicker = object : Runnable {
        override fun run() {
            val elapsed = System.currentTimeMillis() - startedAtMillis
            if (ZaiForegroundPolicy.mustCheckpoint(elapsed) && !checkpointed) {
                checkpointed = true
                checkpointHook?.invoke()
                updateNotification(ZaiForegroundPolicy.checkpointMessage)
                // Step down gracefully: queue persisted, notification posted —
                // release foreground priority but let the process finish the
                // turn it can still finish.
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
                return
            }
            handler.postDelayed(this, CHECKPOINT_TICK_MILLIS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_START -> {
                startedAtMillis = System.currentTimeMillis()
                checkpointed = false
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(
                        title = intent?.getStringExtra(EXTRA_TITLE) ?: "Z.ai is working",
                        detail = intent?.getStringExtra(EXTRA_DETAIL) ?: lastDetail,
                        canStop = true,
                    ),
                )
                acquireWakeLock()
                handler.removeCallbacks(checkpointTicker)
                handler.postDelayed(checkpointTicker, CHECKPOINT_TICK_MILLIS)
            }
            ACTION_PROGRESS -> {
                if (startedAtMillis == 0L) return START_NOT_STICKY
                intent?.getStringExtra(EXTRA_DETAIL)?.takeIf(String::isNotBlank)?.let { lastDetail = it }
                updateNotification(lastDetail)
            }
            ACTION_STOP -> {
                ZaiEngineStopController.requestStop()
                updateNotification("Stopping…")
            }
            ACTION_COMPLETE, ACTION_FAILED, ACTION_NEEDS_ATTENTION -> {
                val (title, failed) = when (intent?.action) {
                    ACTION_COMPLETE -> "Z.ai answered" to false
                    ACTION_NEEDS_ATTENTION -> "Z.ai needs your attention" to true
                    else -> "Z.ai task ended" to true
                }
                val detail = intent?.getStringExtra(EXTRA_DETAIL) ?: lastDetail
                finishWithNotification(title, detail, failed)
            }
        }
        return START_NOT_STICKY
    }

    private fun finishWithNotification(title: String, detail: String, failed: Boolean) {
        releaseWakeLock()
        handler.removeCallbacks(checkpointTicker)
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).notify(
            RESULT_NOTIFICATION_ID,
            NotificationCompat.Builder(this, RESULT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(detail)
                .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .setCategory(if (failed) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build(),
        )
        stopSelf()
    }

    private fun updateNotification(detail: String) {
        lastDetail = detail
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(
                title = "Z.ai is working",
                detail = detail,
                canStop = !checkpointed,
            ),
        )
    }

    private fun buildNotification(title: String, detail: String, canStop: Boolean): android.app.Notification {
        val builder = NotificationCompat.Builder(this, RUNNING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(detail)
            .setContentIntent(openAppIntent())
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (canStop) {
            val stopIntent = PendingIntent.getService(
                this,
                2,
                Intent(this, ZaiEngineService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, "Stop", stopIntent)
        }
        return builder.build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        1,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "com.jarves.mh:zai-engine")
            .apply { acquire(ZaiForegroundPolicy.WAKE_LOCK_MILLIS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    override fun onDestroy() {
        handler.removeCallbacks(checkpointTicker)
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.jarves.mh.ZAI_ENGINE_START"
        const val ACTION_PROGRESS = "com.jarves.mh.ZAI_ENGINE_PROGRESS"
        const val ACTION_STOP = "com.jarves.mh.ZAI_ENGINE_STOP"
        const val ACTION_COMPLETE = "com.jarves.mh.ZAI_ENGINE_COMPLETE"
        const val ACTION_FAILED = "com.jarves.mh.ZAI_ENGINE_FAILED"
        const val ACTION_NEEDS_ATTENTION = "com.jarves.mh.ZAI_ENGINE_ATTENTION"
        const val EXTRA_TITLE = "title"
        const val EXTRA_DETAIL = "detail"

        private const val RUNNING_CHANNEL_ID = "zai-engine"
        private const val RESULT_CHANNEL_ID = "task-results"
        private const val NOTIFICATION_ID = 43
        private const val RESULT_NOTIFICATION_ID = 44
        private const val CHECKPOINT_TICK_MILLIS = 60_000L

        /**
         * Set by the ViewModel: persists the task queue before the dataSync
         * window forces the service down (checkpoint-and-notify, never a
         * silent kill).
         */
        @Volatile
        var checkpointHook: (() -> Unit)? = null

        fun ensureNotificationChannels(context: android.content.Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(RUNNING_CHANNEL_ID, "Z.ai engine running", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Keeps the Z.ai engine alive while it works, even with the screen off"
                },
            )
            manager.createNotificationChannel(
                NotificationChannel(RESULT_CHANNEL_ID, "Task results", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Notifies you when a task finishes or needs attention"
                },
            )
        }
    }
}
