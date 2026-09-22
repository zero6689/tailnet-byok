package io.github.zero6689.tailnetbyok.notify

import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Reading a notification channel's importance as the one thing the user cares about.
 *
 * The bug this covers: 0.3.5 posted the "a task finished" notice to a channel created
 * with `IMPORTANCE_DEFAULT`. The notification arrived, showed in the shade, and never
 * popped up — and on Android 8+ the *channel's* importance is what decides that, so
 * no change to the notification itself could have fixed it. "It only goes to the
 * notification bar, it never pops up" is therefore exactly this mapping, and it is
 * worth pinning: the failure mode is a silent, banner-less notification, which looks
 * identical to a watch that never noticed anything.
 *
 * The platform constants are compile-time ints, so this runs on a bare JVM — which is
 * also why the values are asserted against `IMPORTANCE_*` rather than written out:
 * a mirror that drifted from the platform would be a diagnostic that lies.
 */
class TurnChannelAdviceTest {

    @Test
    fun `high and above banner`() {
        assertEquals(TurnChannelAdvice.BANNER, TurnNotifications.channelAdvice(NotificationManager.IMPORTANCE_HIGH))
        assertEquals(TurnChannelAdvice.BANNER, TurnNotifications.channelAdvice(NotificationManager.IMPORTANCE_MAX))
    }

    @Test
    fun `default is the shade without a banner`() {
        // The 0.3.5 channel, and the user's report.
        assertEquals(
            TurnChannelAdvice.SHADE_ONLY,
            TurnNotifications.channelAdvice(NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    @Test
    fun `low and below are silent`() {
        assertEquals(TurnChannelAdvice.SILENT, TurnNotifications.channelAdvice(NotificationManager.IMPORTANCE_LOW))
        assertEquals(TurnChannelAdvice.SILENT, TurnNotifications.channelAdvice(NotificationManager.IMPORTANCE_MIN))
        // IMPORTANCE_NONE is "the user turned this channel off" — still not a banner,
        // and the diagnostics word for it is the same.
        assertEquals(TurnChannelAdvice.SILENT, TurnNotifications.channelAdvice(NotificationManager.IMPORTANCE_NONE))
    }

    @Test
    fun `no channel at all is its own answer`() {
        assertEquals(TurnChannelAdvice.MISSING, TurnNotifications.channelAdvice(null))
    }

    @Test
    fun `the platform's scale is the one that is assumed`() {
        // The ordering is what the `when` in channelAdvice relies on; if a future SDK
        // renumbered the scale, this is where it would show up.
        assertEquals(0, NotificationManager.IMPORTANCE_NONE)
        assertEquals(1, NotificationManager.IMPORTANCE_MIN)
        assertEquals(2, NotificationManager.IMPORTANCE_LOW)
        assertEquals(3, NotificationManager.IMPORTANCE_DEFAULT)
        assertEquals(4, NotificationManager.IMPORTANCE_HIGH)
        assertEquals(5, NotificationManager.IMPORTANCE_MAX)
    }
}
