package io.github.zero6689.tailnetbyok.ui.setup

import io.github.zero6689.tailnetbyok.data.config.AppConfig
import io.github.zero6689.tailnetbyok.domain.SetupLink
import io.github.zero6689.tailnetbyok.domain.SetupLinkRejection
import io.github.zero6689.tailnetbyok.net.ProviderId
import io.github.zero6689.tailnetbyok.net.WebUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a launch walks straight into the DSH screen.
 *
 * This is a plain function of the state, which is why it is here rather than
 * buried in the view model: "does the app open where the user was, or does it
 * make them find their way back" is a product decision, and the cases where the
 * answer must be *no* are the ones worth pinning down.
 */
class UiStateLaunchTest {

    private fun configured(
        host: String = "100.101.102.103",
        provider: ProviderId = ProviderId.SYSTEM_NETWORK,
        hasKey: Boolean = false,
    ) = AppConfig(
        provider = provider,
        hostInput = host,
        hasStoredKey = hasKey,
        acknowledgedSecurityModel = true,
    )

    @Test
    fun `a configured phone goes straight to the DSH screen`() {
        assertTrue(UiState(config = configured()).shouldOpenWebUiOnLaunch)
    }

    @Test
    fun `the embedded route needs its key before there is anything to open`() {
        val withoutKey = configured(provider = ProviderId.EMBEDDED_TSNET, hasKey = false)
        val withKey = configured(provider = ProviderId.EMBEDDED_TSNET, hasKey = true)

        assertFalse(UiState(config = withoutKey).shouldOpenWebUiOnLaunch)
        assertTrue(UiState(config = withKey).shouldOpenWebUiOnLaunch)
    }

    @Test
    fun `an empty target and a target the policy rejects both stay on the fields`() {
        assertFalse(UiState(config = configured(host = "")).shouldOpenWebUiOnLaunch)
        assertFalse(UiState(config = configured(host = "93.184.216.34:80")).shouldOpenWebUiOnLaunch)
    }

    @Test
    fun `a configuration link awaiting a decision wins over the automatic walk`() {
        val state = UiState(
            config = configured(),
            pendingSetup = SetupLink(host = "100.101.102.103"),
        )
        assertFalse(state.shouldOpenWebUiOnLaunch)

        val rejected = UiState(
            config = configured(),
            setupLinkRejection = SetupLinkRejection.CREDENTIAL_FIELD,
        )
        assertFalse(rejected.shouldOpenWebUiOnLaunch)
    }

    @Test
    fun `a scanner already holding the screen is not interrupted`() {
        assertFalse(UiState(config = configured(), scanning = true).shouldOpenWebUiOnLaunch)
    }

    @Test
    fun `the screen is not opened twice`() {
        val alreadyOpen = UiState(config = configured(), webUrl = WebUrl("http://127.0.0.1:1/"))
        assertFalse(alreadyOpen.shouldOpenWebUiOnLaunch)
        assertFalse(UiState(config = configured(), isOpeningWebUi = true).shouldOpenWebUiOnLaunch)
    }

    @Test
    fun `the acknowledgement is not what gates the walk`() {
        // The note is a card on the settings screen, not a lock on the app: a user
        // who configured everything and never tapped "I understand" must still
        // land where they were, or the onboarding never ends.
        val unacknowledged = AppConfig(
            provider = ProviderId.SYSTEM_NETWORK,
            hostInput = "100.101.102.103",
            acknowledgedSecurityModel = false,
        )
        assertTrue(UiState(config = unacknowledged).shouldOpenWebUiOnLaunch)
    }
}
