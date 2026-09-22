package io.github.zero6689.tailnetbyok.domain

/**
 * What happened to a "a task finished" notification.
 *
 * This exists because the previous version returned nothing, and every reason it
 * declined to post was an early `return` — so "the app did not notify me" had four
 * possible causes and no way to tell them apart. A user cannot debug that, and
 * neither can anyone reading a bug report that says only "no notification".
 */
enum class TurnNoticeOutcome {
    /** Posted. */
    POSTED,

    /** The app was on screen, so the notification would have been noise. */
    SKIPPED_IN_FOREGROUND,

    /** The user (or a channel setting) has notifications off for this app. */
    SKIPPED_NOTIFICATIONS_OFF,

    /** Android 13+: `POST_NOTIFICATIONS` was never granted. */
    SKIPPED_NO_PERMISSION,

    /** The system refused the post itself. */
    FAILED,
}

/**
 * The decision, as a function of the three facts that make it.
 *
 * Pulled out of `TurnNotifications` so it can be tested on the JVM: the ordering
 * matters (a foreground app must not post even when everything is permitted), and
 * the Android calls that produce the inputs are the parts that cannot be tested
 * without a device.
 */
object TurnNotificationDecision {

    fun decide(
        inForeground: Boolean,
        notificationsEnabled: Boolean,
        permissionGranted: Boolean,
    ): TurnNoticeOutcome = when {
        inForeground -> TurnNoticeOutcome.SKIPPED_IN_FOREGROUND
        !notificationsEnabled -> TurnNoticeOutcome.SKIPPED_NOTIFICATIONS_OFF
        !permissionGranted -> TurnNoticeOutcome.SKIPPED_NO_PERMISSION
        else -> TurnNoticeOutcome.POSTED
    }
}
