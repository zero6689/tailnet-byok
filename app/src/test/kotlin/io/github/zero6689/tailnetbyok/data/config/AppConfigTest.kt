package io.github.zero6689.tailnetbyok.data.config

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the updater looks, as a property of the configuration.
 *
 * The case worth pinning is the last one: an empty target must not produce a
 * URL. The first build of the update panel printed `http://:3080` as "the source
 * it will use" — a string that is not a URL, shown to a user who has not typed a
 * host yet. A placeholder that lies is worse than no placeholder.
 */
class AppConfigTest {

    @Test
    fun `the update source falls back to the target's origin`() {
        val config = AppConfig(hostInput = "100.101.102.103", port = 3080)
        assertEquals("http://100.101.102.103:3080", config.updateBase)
    }

    @Test
    fun `scheme and port are the target's, and the path is not included`() {
        val config = AppConfig(
            scheme = "https",
            hostInput = "phone.tailnet-name.ts.net",
            port = 8443,
            path = "/health",
        )
        assertEquals("https://phone.tailnet-name.ts.net:8443", config.updateBase)
    }

    @Test
    fun `an explicit update source wins, without a trailing slash`() {
        val config = AppConfig(
            hostInput = "100.101.102.103",
            updateUrl = "  http://192.0.2.10:8089/mirror/  ",
        )
        assertEquals("http://192.0.2.10:8089/mirror", config.updateBase)
    }

    @Test
    fun `an explicit update source is used even with no target`() {
        val config = AppConfig(updateUrl = "http://192.0.2.10:8089")
        assertEquals("http://192.0.2.10:8089", config.updateBase)
    }

    @Test
    fun `no target and no source is empty, not a URL with no host`() {
        assertEquals("", AppConfig().updateBase)
        assertEquals("", AppConfig(updateUrl = "   ").updateBase)
        // The shape of the bug this guards: scheme and port with an empty host.
        assertEquals(false, AppConfig().updateBase.contains("://"))
    }
}
