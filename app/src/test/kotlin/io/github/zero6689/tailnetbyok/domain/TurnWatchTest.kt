package io.github.zero6689.tailnetbyok.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules behind the "a task finished" notification.
 *
 * Every case here is a way the notification could be wrong: firing on the first
 * look (a task that was already idle when the app opened), firing twice, or —
 * the expensive one — reading an unusable answer as "everything stopped".
 */
class TurnWatchTest {

    private fun busy(id: String, title: String? = null) = SessionRun(id, title, running = true)
    private fun idle(id: String, title: String? = null) = SessionRun(id, title, running = false)

    @Test
    fun `the first look is a baseline, not a notification`() {
        val watch = TurnWatch()

        assertTrue(watch.observe(listOf(idle("a"), idle("b"))).isEmpty())
        assertTrue(watch.observe(listOf(idle("a"), idle("b"))).isEmpty())
    }

    @Test
    fun `a session that stops running is reported exactly once`() {
        val watch = TurnWatch()

        assertTrue(watch.observe(listOf(busy("a"))).isEmpty())
        assertEquals(listOf("a"), watch.observe(listOf(idle("a"))).map { it.id })
        assertTrue(watch.observe(listOf(idle("a"))).isEmpty())
    }

    @Test
    fun `only the session that stopped is reported`() {
        val watch = TurnWatch()

        assertTrue(watch.observe(listOf(busy("a"), busy("b"))).isEmpty())

        val finished = watch.observe(listOf(idle("a"), busy("b")))
        assertEquals(listOf("a"), finished.map { it.id })

        val second = watch.observe(listOf(idle("a"), idle("b")))
        assertEquals(listOf("b"), second.map { it.id })
    }

    @Test
    fun `a session that disappears is not a finish`() {
        val watch = TurnWatch()

        assertTrue(watch.observe(listOf(busy("a"))).isEmpty())
        // "It is not there any more" is a different event from "it finished", and
        // a notification for work that may never have completed is worse than none.
        assertTrue(watch.observe(emptyList()).isEmpty())
    }

    @Test
    fun `a session that starts while the app watches is reported when it stops`() {
        val watch = TurnWatch()

        assertTrue(watch.observe(listOf(idle("a"))).isEmpty())
        assertTrue(watch.observe(listOf(busy("a"))).isEmpty())

        assertEquals(listOf("a"), watch.observe(listOf(idle("a"))).map { it.id })
    }

    @Test
    fun `a finished session carries the title the target gave it`() {
        val watch = TurnWatch()

        watch.observe(listOf(busy("a", title = "Ship the release")))
        val finished = watch.observe(listOf(idle("a", title = "Ship the release")))

        assertEquals("Ship the release", finished.single().title)
    }

    // -- Reading the target's answer ------------------------------------------

    private val answer = """
        {"type":"gateway/response","rpcId":"r1","result":{"ok":true,"value":{"items":[
          {"sessionId":"session-a","running":true,"blank":false,
           "projections":{"asOfSeq":10,"values":{"title":"Deploy the thing","goal":null}}},
          {"sessionId":"session-b","running":false,
           "projections":{"values":{"title":null}}},
          {"running":false}
        ]}}}
    """.trimIndent()

    @Test
    fun `reads the sessions out of an answer`() {
        val sessions = SessionListProtocol.parseSessions(answer)

        assertEquals(
            listOf(
                SessionRun("session-a", "Deploy the thing", running = true),
                SessionRun("session-b", null, running = false),
            ),
            sessions,
        )
    }

    @Test
    fun `an answer with no sessions in it is an answer`() {
        val body = """{"result":{"ok":true,"value":{"items":[]}}}"""

        assertEquals(emptyList<SessionRun>(), SessionListProtocol.parseSessions(body))
    }

    @Test
    fun `a refused answer is not an empty list`() {
        val body = """{"result":{"ok":false,"error":{"code":"gateway/internal"}}}"""

        // Null, never an empty list: an empty list reads as "every session
        // stopped", which is the one notification that must not be invented.
        assertNull(SessionListProtocol.parseSessions(body))
    }

    @Test
    fun `a body that is not an answer at all is not a list`() {
        assertNull(SessionListProtocol.parseSessions("<html>login</html>"))
        assertNull(SessionListProtocol.parseSessions(""))
        assertNull(SessionListProtocol.parseSessions("""{"result":{"ok":true}}"""))
    }

    @Test
    fun `the request is the gateway's envelope`() {
        val request = SessionListProtocol.request("rpc-7")

        assertTrue(request, request.contains("\"client-request\""))
        assertTrue(request, request.contains("\"rpcId\":\"rpc-7\""))
        assertTrue(request, request.contains("\"method\":\"session/list\""))
        assertTrue(request, request.contains("\"_request\""))
    }
}
