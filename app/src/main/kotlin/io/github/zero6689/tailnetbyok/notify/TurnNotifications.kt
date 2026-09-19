package io.github.zero6689.tailnetbyok.notify

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.zero6689.tailnetbyok.MainActivity
import io.github.zero6689.tailnetbyok.R
import io.github.zero6689.tailnetbyok.core.log.SafeLog

/**
 * Whether any activity is visible.
 *
 * Counted rather than flagged, because activities overlap: a flag set on start
 * and cleared on stop reports "background" in the middle of every transition,
 * which is exactly when a turn tends to finish.
 */
object AppForeground : Application.ActivityLifecycleCallbacks {

    @Volatile
    var isForeground: Boolean = false
        private set

    private var started = 0

    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        started += 1
        isForeground = true
    }

    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
        isForeground = started > 0
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

/**
 * The one notification this app posts: a session stopped running.
 *
 * What it deliberately does *not* carry is any of the conversation. A
 * notification is read on a lock screen, often with someone else in the room, and
 * "what the agent said" is the part worth keeping off it. The session's own title
 * is included — it is the name the user gave the work, and without it the
 * notification says nothing actionable.
 */
object TurnNotifications {

    private const val CHANNEL_ID = "turns"
    private const val NOTIFICATION_ID = 1001

    /**
     * Posts "a task finished", unless the app is in front of the user.
     *
     * The foreground check is the whole reason this is not simply "always post":
     * someone watching the DSH screen sees the turn end, and a system notification
     * on top of that is noise. The check lives here rather than at the call site so
     * that no future caller has to remember it.
     */
    fun turnFinished(context: Context, sessionTitle: String?) {
        if (AppForeground.isForeground) return

        // Two different questions, both asked in the one place the call happens:
        // the user's answer inside the app, and the platform's (Android 13+, asked
        // once the first time the DSH screen opens). The platform check is also
        // what makes this call legal rather than a permission violation.
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notify_turn_title))
            .setContentText(
                sessionTitle?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.notify_turn_fallback),
            )
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }.onFailure {
            // A notification that cannot be posted (permission revoked between the
            // check and the call) must never take the poll loop down with it.
            SafeLog.w(TAG, "could not post the turn notification", it)
        }
    }

    /** Creates the channel once. The system ignores a repeat of the same id. */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notify_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.notify_channel_description)
            },
        )
    }

    /** Tapping the notification opens the app, not a second copy of it. */
    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private const val TAG = "TurnNotifications"
}
