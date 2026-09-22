package io.github.zero6689.tailnetbyok.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.zero6689.tailnetbyok.MainActivity
import io.github.zero6689.tailnetbyok.R
import io.github.zero6689.tailnetbyok.core.log.SafeLog

/**
 * Keeps this process alive while the DSH screen is watching a running task.
 *
 * # Why a service, and why only now
 *
 * The session poll lives in the settings view model, and Android 12+ *freezes* a
 * backgrounded app's process: not killing it, freezing it — timers stop, the poll
 * stops, and the "a task finished" notification never happens. That is what the
 * first real test of the feature hit. A foreground service is the only supported
 * way to keep doing work with the app off screen, so the watch now raises one for
 * as long as it is watching.
 *
 * # Why the notification is silent, and why it exists at all
 *
 * A foreground service must show a notification; there is no way around that, and
 * this project's earlier position ("no foreground service, a permanent notification
 * costs more than it earns") is exactly the trade this reverses — deliberately, on
 * the user's request, because a notification that never arrives costs more. The
 * channel is IMPORTANCE_LOW: visible in the shade, no sound, no vibration, and it
 * says what it is for.
 *
 * # The bound on it
 *
 * It is started when the DSH screen acquires its route and stopped when the screen
 * releases it (or when the process is replaced). It does not watch with the DSH
 * screen closed: the route into the tailnet, and the session cookie that goes with
 * it, are released when the screen is closed — that release is a security property
 * of this app, not an oversight, so keeping the watch alive past the screen would
 * mean keeping a live path into the user's server alive with it. See
 * `docs/SECURITY-MODEL.md`.
 */
class TurnWatchService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification(this))
        running = true
        SafeLog.i(TAG, "turn watch foreground service started")
        // Not sticky: if Android restarts this process after a kill, there is no
        // view model, no route and no cookie to poll with, so a restart would be a
        // silent no-op with a notification attached — worse than not coming back.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        SafeLog.i(TAG, "turn watch foreground service stopped")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TurnWatchService"
        private const val CHANNEL_ID = "turn-watch"
        const val NOTIFICATION_ID = 1002

        /** Read by the diagnostics line; the only thing outside this class that cares. */
        @Volatile
        var running: Boolean = false
            private set

        /**
         * Asks for the service, and reports whether the platform allowed it.
         *
         * `startForegroundService` throws `ForegroundServiceStartNotAllowedException`
         * when the app is already in the background and has no exemption. That is a
         * legitimate refusal with a legitimate consequence (the watch keeps polling
         * until the process is frozen), so it is caught and reported rather than
         * allowed to take the caller down — and the diagnostics line says which
         * happened, because "the notification never came" has to be answerable.
         */
        fun start(context: Context): Boolean = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, TurnWatchService::class.java))
            true
        }.getOrElse {
            SafeLog.w(TAG, "the platform refused to start the watch service", it)
            false
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TurnWatchService::class.java)) }
            running = false
        }

        fun ensureChannel(context: Context) {
            // No SDK_INT guard: this app's minSdk is 26, which is the version that
            // introduced notification channels, so the check would be dead code.
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notify_watch_channel_name),
                    // LOW, not DEFAULT: this row exists because the platform
                    // requires one, so it must not buzz. The notification that
                    // matters is the other one, and it uses its own channel.
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.notify_watch_channel_description)
                    setShowBadge(false)
                },
            )
        }

        private fun notification(context: Context): Notification {
            ensureChannel(context)
            val open = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.notify_watch_title))
                .setContentText(context.getString(R.string.notify_watch_text))
                .setContentIntent(
                    PendingIntent.getActivity(
                        context,
                        0,
                        open,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        }
    }
}
