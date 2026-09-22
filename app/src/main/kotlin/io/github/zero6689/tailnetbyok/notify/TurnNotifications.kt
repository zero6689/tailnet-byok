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
import io.github.zero6689.tailnetbyok.domain.TurnNoticeOutcome
import io.github.zero6689.tailnetbyok.domain.TurnNotificationDecision

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
 * What a notification channel's importance means for the user.
 *
 * The distinction that matters is [BANNER] against everything else: below it a
 * notification still arrives, still shows in the shade, and still makes whatever
 * sound the channel allows — it simply never *pops up*, which is the difference
 * between "the app told me" and "I found it later".
 */
enum class TurnChannelAdvice { MISSING, SILENT, SHADE_ONLY, BANNER }

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

    /**
     * The channel the finish notice is posted to.
     *
     * The `-v2` suffix is load-bearing, not cosmetic. A channel's importance is
     * fixed when it is created: an app may lower it, never raise it, and a user's own
     * setting always wins. The 0.3.5 channel was created with `IMPORTANCE_DEFAULT`,
     * so on every phone that already had it the notice could only ever be a silent
     * row in the shade — and editing that constant would have changed nothing on any
     * installed device. A new id is the only way to get a channel that can banner;
     * the old one is removed in [ensureChannel] so the app's notification settings do
     * not keep a dead row.
     */
    private const val CHANNEL_ID = "turn-finished-v2"

    /** 0.3.5–0.3.6: `IMPORTANCE_DEFAULT`, and therefore banner-less forever. */
    private const val LEGACY_CHANNEL_ID = "turns"

    private const val NOTIFICATION_ID = 1001

    /**
     * Posts "a task finished", unless the app is in front of the user.
     *
     * The foreground check is the whole reason this is not simply "always post":
     * someone watching the DSH screen sees the turn end, and a system notification
     * on top of that is noise. The check lives here rather than at the call site so
     * that no future caller has to remember it.
     *
     * Returns *why* it did what it did, and that return value is not decoration: it
     * is what the settings screen's diagnostics report. Until this returned an
     * outcome, every decline was a silent `return`, so "the app never told me my
     * task finished" had four indistinguishable causes — the app was on screen, the
     * user had notifications off, the permission was never granted, or the post
     * failed — and the only way to find out which was to guess.
     */
    fun turnFinished(context: Context, sessionTitle: String?): TurnNoticeOutcome {
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

        // Two different questions, both asked in the one place the call happens:
        // the platform's answer for this app, and the user's runtime grant. Both
        // are required, and the platform check is also what makes the notify() call
        // legal rather than a permission violation.
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()

        val outcome = TurnNotificationDecision.decide(
            inForeground = AppForeground.isForeground,
            notificationsEnabled = enabled,
            permissionGranted = permissionGranted,
        )
        if (outcome != TurnNoticeOutcome.POSTED) return outcome

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
            // The priority line is the pre-channel equivalent of the channel's
            // importance, and it is the same value on purpose; minSdk is 26, so it
            // is belt and braces rather than a live path.
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        return runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            TurnNoticeOutcome.POSTED
        }.getOrElse {
            // A notification that cannot be posted (permission revoked between the
            // check and the call) must never take the poll loop down with it.
            SafeLog.w(TAG, "could not post the turn notification", it)
            TurnNoticeOutcome.FAILED
        }
    }

    /**
     * Creates the channel once, and removes the one that could never banner.
     *
     * `IMPORTANCE_HIGH` is the whole point: on Android 8 and later the channel's
     * importance — not the builder's priority — decides whether a notification is a
     * heads-up banner, and only `HIGH` or above banners. 0.3.5 shipped this channel
     * as `IMPORTANCE_DEFAULT` and the user's report was exactly that outcome: the
     * notice appeared in the shade, silently, with no pop-up. A user can still lower
     * it afterwards (that is their call, and the diagnostics panel reports it), but
     * the value this app creates it with is the one that can banner.
     */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        // The pre-0.3.7 channel. Deleting it also drops any notification posted to
        // it, which is the point: it is superseded, and a stale entry in the app's
        // notification settings is a thing to explain rather than a thing to use.
        runCatching { manager.deleteNotificationChannel(LEGACY_CHANNEL_ID) }

        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notify_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.notify_channel_description)
            },
        )
    }

    /**
     * The channel's *effective* importance, or null when it has not been created.
     *
     * Read back from the system rather than remembered, because the answer the user
     * sees is the system's: raising it is the app's job once, and after that only
     * Android's notification settings can change it.
     */
    fun channelImportance(context: Context): Int? =
        context.getSystemService(NotificationManager::class.java)
            ?.getNotificationChannel(CHANNEL_ID)
            ?.importance

    /**
     * What that importance means, as one word for the diagnostics panel.
     *
     * This is the line that answers "will it pop up": a notice that reaches the shade
     * and no further is not a broken watch, it is a channel below `HIGH`, and the two
     * look identical from the outside.
     */
    fun channelAdvice(importance: Int?): TurnChannelAdvice = when {
        importance == null -> TurnChannelAdvice.MISSING
        importance >= NotificationManager.IMPORTANCE_HIGH -> TurnChannelAdvice.BANNER
        importance == NotificationManager.IMPORTANCE_DEFAULT -> TurnChannelAdvice.SHADE_ONLY
        else -> TurnChannelAdvice.SILENT
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
