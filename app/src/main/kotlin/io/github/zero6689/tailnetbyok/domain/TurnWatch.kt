package io.github.zero6689.tailnetbyok.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One session, as the target's own RPC describes it. */
data class SessionRun(val id: String, val title: String?, val running: Boolean)

/**
 * Notices the moment a session stops running.
 *
 * "A task finished" is a *transition*, not a state. A session that is already
 * idle when the app looks is not news, and a session that stays idle stays not
 * news. Only running → not running is worth a notification, and only once, which
 * is why this is a class with memory rather than a function over a list.
 *
 * The first observation is the baseline: a task that was already in flight before
 * the app opened has to be seen running once before its end means anything.
 * Otherwise every session that happened to be idle at start-up would announce
 * itself as finished.
 */
class TurnWatch {

    private val running = mutableSetOf<String>()

    /**
     * The sessions that were running at the previous look and are not now.
     *
     * A session that disappears from the list while running is *not* reported:
     * the honest reading is "it is not there any more", which is a different event
     * from "it finished", and inventing the second from the first would put a
     * notification on the phone for something that may never have run to
     * completion.
     */
    fun observe(sessions: List<SessionRun>): List<SessionRun> {
        val finished = sessions.filter { !it.running && it.id in running }
        running.clear()
        running += sessions.filter { it.running }.map { it.id }
        return finished
    }
}

/**
 * Reading the target's `session/list` answer.
 *
 * This is the app talking to the same RPC gateway the page talks to, rather than
 * reading the page: endpoints outlive class names, CSS-module hashes and DOM
 * shape, so a DSH update cannot silently turn the notification off.
 *
 * The envelope is the gateway's, quoted here in one place so that a change to it
 * is a change to one constant:
 * `{"type":"client-request","rpcId":…,"method":"session/list","payload":{"args":{"_request":{}}}}`.
 */
object SessionListProtocol {

    const val METHOD = "session/list"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** The request body for one poll. [rpcId] only has to be unique per request. */
    fun request(rpcId: String): String =
        """{"type":"client-request","rpcId":"$rpcId","method":"$METHOD",""" +
            """"payload":{"args":{"_request":{}}}}"""

    /**
     * The sessions in an answer, or null when the answer is not one.
     *
     * Null and "no sessions" are different answers on purpose. A refused or
     * unreadable request that came back as an empty list would read as *every*
     * session having stopped, which is exactly the notification that must not be
     * sent — so an unusable answer has to be distinguishable from an empty one.
     */
    fun parseSessions(body: String): List<SessionRun>? {
        val envelope = runCatching { json.decodeFromString<Envelope>(body) }.getOrNull() ?: return null
        val result = envelope.result ?: return null
        if (!result.ok) return null
        val items = result.value?.items ?: return null
        return items.mapNotNull { item ->
            val id = item.sessionId ?: return@mapNotNull null
            SessionRun(id = id, title = item.projections?.`values`?.title, running = item.running)
        }
    }

    @Serializable
    private data class Envelope(val result: RpcResult? = null)

    @Serializable
    private data class RpcResult(val ok: Boolean = false, val value: RpcValue? = null)

    @Serializable
    private data class RpcValue(val items: List<Item> = emptyList())

    @Serializable
    private data class Item(
        val sessionId: String? = null,
        val running: Boolean = false,
        val projections: Projections? = null,
    )

    @Serializable
    private data class Projections(val `values`: Values? = null)

    @Serializable
    private data class Values(val title: String? = null)
}
