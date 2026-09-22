package io.github.zero6689.tailnetbyok.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The table that decides whether "a task finished" reaches the user.
 *
 * The order is the point. A user watching the DSH screen must not get a
 * notification on top of the thing they are already reading, and that has to win
 * over every "yes" below it; and when nothing can be posted, which *reason* is
 * reported is what makes the difference between a bug report that can be acted on
 * and one that says "it did not work".
 */
class TurnNotificationDecisionTest {

    @Test
    fun `everything allowed and the app is elsewhere posts`() {
        assertEquals(
            TurnNoticeOutcome.POSTED,
            TurnNotificationDecision.decide(
                inForeground = false,
                notificationsEnabled = true,
                permissionGranted = true,
            ),
        )
    }

    @Test
    fun `a foreground app is never notified about its own screen`() {
        assertEquals(
            TurnNoticeOutcome.SKIPPED_IN_FOREGROUND,
            TurnNotificationDecision.decide(
                inForeground = true,
                notificationsEnabled = true,
                permissionGranted = true,
            ),
        )
        // Even when the other two facts say no: the answer is still "not needed",
        // because that is the reason a user would have to act on.
        assertEquals(
            TurnNoticeOutcome.SKIPPED_IN_FOREGROUND,
            TurnNotificationDecision.decide(
                inForeground = true,
                notificationsEnabled = false,
                permissionGranted = false,
            ),
        )
    }

    @Test
    fun `notifications switched off in Android is reported as that, not as a permission problem`() {
        assertEquals(
            TurnNoticeOutcome.SKIPPED_NOTIFICATIONS_OFF,
            TurnNotificationDecision.decide(
                inForeground = false,
                notificationsEnabled = false,
                permissionGranted = true,
            ),
        )
    }

    @Test
    fun `a missing runtime permission is its own answer`() {
        assertEquals(
            TurnNoticeOutcome.SKIPPED_NO_PERMISSION,
            TurnNotificationDecision.decide(
                inForeground = false,
                notificationsEnabled = true,
                permissionGranted = false,
            ),
        )
    }
}
