package com.wm.starcam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.wm.starcam.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * What a running session wants the notification to say, plus the hook its Stop action pulls.
 *
 * A plain singleton: the service and the ViewModel live in the same process, and binding a
 * Service to pass four fields across would be machinery for its own sake.
 */
object SessionBeacon {

    data class Status(
        val title: String,
        val text: String,
        val current: Int = 0,
        val total: Int = 0,
    )

    private val _status = MutableStateFlow<Status?>(null)
    val status: StateFlow<Status?> = _status

    fun update(s: Status?) {
        _status.value = s
    }

    /**
     * Set by the ViewModel while a session runs, cleared when it ends.
     *
     * Two of them, because "stop" from a lock screen is ambiguous and the two answers are very
     * different: [onFinish] keeps the frames and stacks them, [onCancel] throws the session away.
     */
    @Volatile
    var onFinish: (() -> Unit)? = null

    @Volatile
    var onCancel: (() -> Unit)? = null

    fun clearActions() {
        onFinish = null
        onCancel = null
    }
}

/**
 * Keeps a capture session alive while the app is not on screen.
 *
 * Two separate things break a long session otherwise. Android revokes camera access to
 * background apps, so locking the phone or switching to a star map kills a 20-minute burst. And
 * with the screen off the CPU is free to suspend between frames, so even a surviving session
 * stalls. A camera-type foreground service fixes the first, the partial wake lock fixes the
 * second, and together they are what makes it safe to let the display sleep behind the shield.
 *
 * The service owns no camera state — the engine keeps running in the ViewModel exactly as
 * before. All this does is hold the process in the foreground and draw a notification.
 */
class CaptureService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collector: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            // The service does not stop itself here: the session's own teardown calls stop(),
            // so the notification keeps reporting the stack/save that follows a "Finish now".
            ACTION_FINISH -> {
                AppLog.i(TAG, "notification: Finish now")
                SessionBeacon.onFinish?.invoke()
                return START_NOT_STICKY
            }
            ACTION_CANCEL -> {
                AppLog.i(TAG, "notification: Cancel")
                SessionBeacon.onCancel?.invoke()
                return START_NOT_STICKY
            }
        }

        // Must happen within 5 s of startForegroundService, so before anything that can block.
        // If the system refuses (it can, for camera-type services started at the wrong moment),
        // the session would lose the camera the moment the screen goes off — loudly logged, and
        // the service stops itself rather than being killed for never reaching the foreground.
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, build(SessionBeacon.status.value),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            )
            AppLog.i(TAG, "foreground service running (camera type)")
        } catch (t: Throwable) {
            AppLog.e(TAG, "startForeground REFUSED — the session will not survive the screen going off", t)
            stopSelf()
            return START_NOT_STICKY
        }
        acquireWakeLock()

        if (collector == null) {
            val manager = getSystemService(NotificationManager::class.java)
            collector = scope.launch {
                // The ViewModel already samples the session at 1 Hz, so this is one notification
                // update per second at most - fast enough to feel live, slow enough not to spam.
                SessionBeacon.status.collect { manager.notify(NOTIF_ID, build(it)) }
            }
        }
        // Deliberately not sticky: a restarted service with no session behind it would show a
        // notification for work that is not happening.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        AppLog.i(TAG, "foreground service stopped")
        collector = null
        scope.cancel()
        // Explicit: a notify() already in flight when the scope was cancelled could otherwise
        // re-post the ongoing notification for a session that has finished.
        runCatching { getSystemService(NotificationManager::class.java).cancel(NOTIF_ID) }
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StarCam:capture").apply {
                setReferenceCounted(false)
                // A hard ceiling well past the longest session (60-minute trails): a bug that
                // fails to release must not be able to flatten the battery overnight.
                acquire(4 * 60 * 60 * 1000L)
            }
            AppLog.d(TAG, "partial wake lock held")
        }.onFailure { AppLog.e(TAG, "wake lock failed", it) }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    private fun build(status: SessionBeacon.Status?): Notification {
        ensureChannel()
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val finish = PendingIntent.getService(
            this, 1,
            Intent(this, CaptureService::class.java).setAction(ACTION_FINISH),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = PendingIntent.getService(
            this, 2,
            Intent(this, CaptureService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_star_vector)
            .setContentTitle(status?.title ?: "StarCam")
            .setContentText(status?.text ?: "capturing")
            .setContentIntent(open)
            .addAction(0, "Finish now", finish)
            .addAction(0, "Cancel", cancel)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        if (status != null && status.total > 0) {
            b.setProgress(status.total, status.current, false)
        } else {
            b.setProgress(0, 0, true)
        }
        return b.build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, "Capture session", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progress of a running StarCam capture."
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        )
    }

    companion object {
        private const val TAG = "service"
        private const val CHANNEL_ID = "capture"
        private const val NOTIF_ID = 1
        private const val ACTION_FINISH = "com.wm.starcam.FINISH_SESSION"
        private const val ACTION_CANCEL = "com.wm.starcam.CANCEL_SESSION"

        fun start(context: Context) {
            val i = Intent(context, CaptureService::class.java)
            runCatching { context.startForegroundService(i) }
                .onFailure { AppLog.e(TAG, "could not start capture service", it) }
        }

        fun stop(context: Context) {
            SessionBeacon.update(null)
            runCatching { context.stopService(Intent(context, CaptureService::class.java)) }
        }
    }
}
