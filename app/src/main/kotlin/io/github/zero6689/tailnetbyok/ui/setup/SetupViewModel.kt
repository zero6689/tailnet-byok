package io.github.zero6689.tailnetbyok.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.zero6689.tailnetbyok.BuildConfig
import io.github.zero6689.tailnetbyok.R
import io.github.zero6689.tailnetbyok.core.log.Redact
import io.github.zero6689.tailnetbyok.core.log.SafeLog
import io.github.zero6689.tailnetbyok.core.text.TextRef
import io.github.zero6689.tailnetbyok.data.config.AppConfig
import io.github.zero6689.tailnetbyok.data.config.ConfigRepository
import io.github.zero6689.tailnetbyok.di.AppContainer
import io.github.zero6689.tailnetbyok.domain.ConnectionTester
import io.github.zero6689.tailnetbyok.domain.TailnetAddressPolicy
import io.github.zero6689.tailnetbyok.net.ConnectivityProvider
import io.github.zero6689.tailnetbyok.net.ProviderId
import io.github.zero6689.tailnetbyok.net.ProviderRegistry
import io.github.zero6689.tailnetbyok.net.ProviderStatus
import io.github.zero6689.tailnetbyok.net.WebAccess
import io.github.zero6689.tailnetbyok.net.WebUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Screen state, in one object.
 *
 * Every field the UI needs is here, including the ones that are purely
 * presentation, because the alternative — several independently-updating
 * flows — makes "is the test button enabled right now" a question that has to be
 * answered in three places. A single immutable snapshot means the button's state
 * is derived once, in [UiState.canRunTest], and cannot disagree with itself.
 */
data class UiState(
    val config: AppConfig = AppConfig(),
    /** Transient: what is in the key field right now, before it is saved. */
    val keyDraft: String = "",
    val isTesting: Boolean = false,
    /** True while the DSH UI's route is being acquired. */
    val isOpeningWebUi: Boolean = false,
    /**
     * Set while the DSH UI screen is showing: the URL its WebView loads.
     *
     * A [WebUrl], not a `String`, and it is nulled the moment the screen is left.
     * On the embedded-node route this value is a capability token for a loopback
     * port; see `net/ConnectivityProvider.kt` for why it must not be printable.
     */
    val webUrl: WebUrl? = null,
    val steps: List<ConnectionTester.StepResult> = emptyList(),
    val report: ConnectionTester.Report? = null,
    val providerStatus: ProviderStatus? = null,
    val diagnostics: List<String> = emptyList(),
    val hardwareBackedKeystore: Boolean = false,
    /** Set when the embedded provider is unavailable, to explain why. */
    val embeddedUnavailableReason: TextRef? = null,
    val banner: Banner? = null,
) {
    data class Banner(val message: TextRef, val isError: Boolean)

    /** The inline verdict for the host field, recomputed on every keystroke. */
    val addressVerdict: TailnetAddressPolicy.Verdict
        get() = TailnetAddressPolicy.classify(config.hostInput, config.port, config.scheme)

    /**
     * Whether "Test connection" should be tappable.
     *
     * Deliberately conservative: an incomplete configuration produces a report
     * full of failures, which teaches the user nothing. A disabled button with a
     * visible reason is better than an enabled one that always fails.
     */
    val canRunTest: Boolean
        get() = !isTesting &&
            config.hostInput.isNotBlank() &&
            addressVerdict !is TailnetAddressPolicy.Verdict.Rejected &&
            (config.provider != ProviderId.EMBEDDED_TSNET || config.hasStoredKey)

    /**
     * Whether "Open the DSH UI" should be tappable.
     *
     * The same gate as [canRunTest], on purpose: opening the web UI needs
     * exactly what the test needs — a target the address policy accepts, and a
     * credential when the embedded node is the transport — plus the rule that
     * only one route is acquired at a time. Writing this as a second copy of that
     * predicate is how the two buttons drift apart.
     */
    val canOpenWebUi: Boolean
        get() = canRunTest && !isOpeningWebUi

    /** Why the test is disabled, as a message id, or null when it is enabled. */
    val testBlockedReasonRes: Int?
        get() = when {
            isTesting -> null
            config.hostInput.isBlank() -> R.string.test_blocked_no_host
            addressVerdict is TailnetAddressPolicy.Verdict.Rejected ->
                R.string.test_blocked_bad_address
            config.provider == ProviderId.EMBEDDED_TSNET && !config.hasStoredKey ->
                R.string.test_blocked_no_key
            else -> null
        }

    val canSaveKey: Boolean
        get() = keyDraft.isNotBlank()
}

/**
 * Drives the setup screen.
 *
 * # The rule this class follows
 *
 * The plaintext auth key never becomes part of [UiState]. It exists in
 * `keyDraft` only while the user is typing, is written to the encrypted vault on
 * save, and is read back from the vault only for the duration of a connection
 * test. That means a screenshot of the screen, a state dump by a test, or a
 * `toString()` on the state object cannot contain it — `AppConfig` holds a
 * boolean, not the credential.
 */
class SetupViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private val repository: ConfigRepository = container.configRepository

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * Which provider's status flow is currently being collected.
     *
     * Declared before `init` on purpose: Kotlin runs property initialisers and
     * `init` blocks in source order, so a property declared after the `init`
     * block would not yet be assigned when the block runs.
     */
    private var observedProvider: ProviderId? = null

    /**
     * The provider that acquired the DSH UI's route, so the release goes to the
     * same object that opened it. Held as a plain field, not in [UiState]: it is
     * not something the UI renders, and the only thing the screen needs is the
     * URL.
     */
    private var webRoute: ConnectivityProvider? = null

    init {
        _state.value = _state.value.copy(
            hardwareBackedKeystore = repository.isHardwareBacked(),
            embeddedUnavailableReason = ProviderRegistry
                .failureReason(ProviderId.EMBEDDED_TSNET),
        )

        repository.config
            .catch { e ->
                SafeLog.e(TAG, "configuration flow failed", e)
                emit(AppConfig())
            }
            .onEach { config ->
                _state.value = _state.value.copy(
                    config = config,
                    // A stored key means the draft can be cleared: keeping it
                    // would leave the plaintext in memory for no purpose.
                    keyDraft = if (config.hasStoredKey && _state.value.keyDraft.isNotEmpty()) {
                        ""
                    } else {
                        _state.value.keyDraft
                    },
                )
                observeProvider(config.provider)
            }
            .launchIn(viewModelScope)
    }

    /** Keeps [UiState.providerStatus] in step with whichever provider is active. */
    private fun observeProvider(id: ProviderId) {
        if (observedProvider == id) return
        observedProvider = id
        val provider = container.provider(id)
        if (provider == null) {
            _state.value = _state.value.copy(
                providerStatus = ProviderStatus.Unavailable(
                    id,
                    ProviderRegistry.failureReason(id)
                        ?: TextRef.of(R.string.provider_unavailable_generic),
                ),
            )
            return
        }
        provider.status
            .onEach { status -> _state.value = _state.value.copy(providerStatus = status) }
            .launchIn(viewModelScope)
    }

    // -- Form editing --------------------------------------------------------

    fun updateHost(value: String) = persist { it.copy(hostInput = value.trim()) }

    fun updatePort(value: String) {
        // Ignore non-numeric input rather than clamping: silently rewriting what
        // the user typed is how a port ends up wrong without them noticing.
        val port = value.toIntOrNull() ?: return
        if (port in 1..65535) persist { it.copy(port = port) }
    }

    fun updateScheme(value: String) {
        val scheme = value.lowercase()
        if (scheme in setOf("http", "https")) persist { it.copy(scheme = scheme) }
    }

    fun updatePath(value: String) = persist { it.copy(path = value) }

    fun updateControlUrl(value: String) = persist { it.copy(controlUrl = value.trim()) }

    fun updateNodeHostname(value: String) {
        val name = value.trim()
        // A tailnet hostname is a DNS label; anything else will be rejected by
        // the control plane with a less helpful message.
        if (name.isEmpty() || name.all { it.isLetterOrDigit() || it == '-' }) {
            persist { it.copy(nodeHostname = name.ifEmpty { AppConfig.DEFAULT_NODE_HOSTNAME }) }
        }
    }

    fun updateEphemeral(value: Boolean) = persist { it.copy(ephemeral = value) }

    fun acknowledgeSecurityModel() = persist { it.copy(acknowledgedSecurityModel = true) }

    /**
     * Switching provider is not a display preference: it changes whether the
     * auth key is used at all, and whether node state exists. Both are stated in
     * the UI so the switch is not silent.
     */
    fun selectProvider(id: ProviderId) {
        viewModelScope.launch {
            repository.update { it.copy(provider = id) }
            container.releaseProvider(observedProvider ?: id)
            observedProvider = null
            _state.value = _state.value.copy(
                banner = UiState.Banner(
                    message = TextRef.of(
                        if (id == ProviderId.EMBEDDED_TSNET) {
                            R.string.banner_provider_embedded
                        } else {
                            R.string.banner_provider_system
                        },
                    ),
                    isError = false,
                ),
            )
        }
    }

    fun updateKeyDraft(value: String) {
        _state.value = _state.value.copy(keyDraft = value)
    }

    // -- Credential ---------------------------------------------------------

    fun saveKey() {
        viewModelScope.launch {
            val draft = _state.value.keyDraft
            when (val result = repository.storeAuthKey(draft)) {
                is ConfigRepository.KeyWrite.Stored -> {
                    _state.value = _state.value.copy(
                        keyDraft = "",
                        banner = UiState.Banner(
                            TextRef.of(R.string.banner_key_saved),
                            isError = false,
                        ),
                    )
                }
                is ConfigRepository.KeyWrite.Rejected -> {
                    _state.value = _state.value.copy(
                        banner = UiState.Banner(result.reason, isError = true),
                    )
                }
            }
        }
    }

    /**
     * Forgets the credential and the node identity.
     *
     * Both, together, because they are only meaningful as a pair: node state
     * carries the identity the old key produced, and leaving it behind is what
     * makes a freshly pasted key appear to be ignored.
     */
    fun forgetKey() {
        viewModelScope.launch {
            val provider = container.provider(_state.value.config.provider)
            runCatching { provider?.stop() }
            runCatching { provider?.clearState() }
            repository.clearAuthKey()
            _state.value = _state.value.copy(
                keyDraft = "",
                steps = emptyList(),
                report = null,
                banner = UiState.Banner(TextRef.of(R.string.banner_key_forgotten), isError = false),
            )
        }
    }

    // -- Test ---------------------------------------------------------------

    fun runTest() {
        val current = _state.value
        if (!current.canRunTest) return

        viewModelScope.launch {
            _state.value = _state.value.copy(
                isTesting = true,
                steps = emptyList(),
                report = null,
                banner = null,
            )

            val provider = container.provider(current.config.provider)
            val report = container.tester.run(
                config = current.config,
                nodeStateDir = container.nodeStateDir,
                provider = provider,
                readCredentials = { readCredentials() },
                onStep = { step ->
                    _state.value = _state.value.copy(steps = _state.value.steps + step)
                },
            )

            _state.value = _state.value.copy(isTesting = false, report = report)
            // Refresh diagnostics with the node's freshly produced log lines, so
            // the panel below the test result is not stale.
            loadDiagnostics()
        }
    }

    private suspend fun readCredentials(): ConnectionTester.CredentialLookup =
        when (val result = repository.readAuthKey()) {
            is ConfigRepository.KeyRead.Present ->
                ConnectionTester.CredentialLookup.Present(
                    io.github.zero6689.tailnetbyok.net.TailnetCredentials(result.authKey),
                )
            ConfigRepository.KeyRead.Absent -> ConnectionTester.CredentialLookup.Absent
            is ConfigRepository.KeyRead.Unreadable ->
                ConnectionTester.CredentialLookup.Unreadable(result.reason)
        }

    fun loadDiagnostics() {
        viewModelScope.launch {
            val provider = container.provider(_state.value.config.provider) ?: return@launch
            val lines = provider.diagnostics()
            val res = container.appResources
            _state.value = _state.value.copy(
                diagnostics = buildList {
                    // The three labels the app itself contributes are translated;
                    // the provider's own lines are passed through verbatim (they
                    // are meant to be pasted into bug reports).
                    add(res.getString(R.string.diag_provider, res.getString(_state.value.config.provider.labelRes)))
                    add(res.getString(R.string.diag_app_version, BuildConfig.VERSION_NAME))
                    // Only when the build actually bundles a node: the
                    // system-network provider has no library of its own, and a
                    // line reading "node library: null" is worse than no line.
                    provider.libraryVersion?.let {
                        add(res.getString(R.string.diag_library_version, it))
                    }
                    add(res.getString(R.string.diag_hardware_keystore, _state.value.hardwareBackedKeystore.toString()))
                    _state.value.providerStatus?.let {
                        add(res.getString(R.string.diag_node, describe(it, res)))
                    }
                    add(
                        _state.value.config.let { cfg ->
                            res.getString(
                                R.string.diag_target,
                                Redact.hostLabel(cfg.hostInput),
                                cfg.port,
                                cfg.scheme,
                            )
                        },
                    )
                    addAll(lines)
                },
            )
        }
    }

    fun dismissBanner() {
        _state.value = _state.value.copy(banner = null)
    }

    // -- The DSH web UI ------------------------------------------------------

    /**
     * Brings the provider up, then asks it for a route a WebView can take to the
     * target's own interface.
     *
     * # Why this reuses the test rather than doing its own bring-up
     *
     * The embedded node's loopback proxy binds its port whether or not the node
     * is running, and answers every request with a 502 until it is — so the node
     * has to be up before the page is worth showing, or the user gets a 502 with
     * no explanation. The four steps that bring a node up (validate the address,
     * resolve the provider, read the credential, start the node) already exist in
     * [ConnectionTester], and running them here with
     * [ConnectionTester.Scope.BRING_UP] means there is exactly one
     * implementation of them — including their error reporting, which is the
     * same sentence the connection test would have shown for the same failure.
     *
     * # Threading
     *
     * Called from the main dispatcher; the blocking work is not. Every provider
     * call inside is confined to `Dispatchers.IO` — the tester's, and
     * [ConnectivityProvider.openWebAccess], which binds a socket, parses the
     * target and generates a session token.
     */
    fun openWebUi() {
        val current = _state.value
        if (!current.canOpenWebUi) return

        viewModelScope.launch {
            _state.value = _state.value.copy(
                isOpeningWebUi = true,
                steps = emptyList(),
                report = null,
                banner = null,
            )

            val provider = container.provider(current.config.provider)
            val report = container.tester.run(
                config = current.config,
                nodeStateDir = container.nodeStateDir,
                provider = provider,
                readCredentials = { readCredentials() },
                onStep = { step ->
                    _state.value = _state.value.copy(steps = _state.value.steps + step)
                },
                scope = ConnectionTester.Scope.BRING_UP,
            )

            if (provider == null || !report.succeeded) {
                _state.value = _state.value.copy(
                    isOpeningWebUi = false,
                    report = report,
                    banner = UiState.Banner(
                        report.firstFailure?.detail ?: TextRef.of(R.string.web_proxy_unavailable),
                        isError = true,
                    ),
                )
                return@launch
            }

            // The validated address, not the raw field: the same object the
            // bring-up just used, so the page and the health check cannot
            // disagree about where the target is.
            val address = current.addressVerdict.address
            val access = provider.openWebAccess(address, current.config.scheme, current.config.path)

            when (access) {
                is WebAccess.Ready -> {
                    webRoute = provider
                    _state.value = _state.value.copy(
                        isOpeningWebUi = false,
                        report = report,
                        webUrl = access.url,
                    )
                }
                is WebAccess.Refused -> {
                    _state.value = _state.value.copy(
                        isOpeningWebUi = false,
                        report = report,
                        banner = UiState.Banner(access.message, isError = true),
                    )
                }
            }
        }
    }

    /**
     * Leaves the DSH UI screen and gives the route back.
     *
     * The release is not optional: while it is listening, the embedded node's
     * loopback port is a path into the user's server for anything else on the
     * device that holds the token. The URL is dropped here too, so nothing on
     * this side still refers to it once the page is gone.
     */
    fun closeWebUi() {
        if (_state.value.webUrl == null) return
        _state.value = _state.value.copy(webUrl = null)
        releaseWebRoute()
    }

    private fun releaseWebRoute() {
        val provider = webRoute ?: return
        webRoute = null
        // A release that throws must not take the screen's exit down with it.
        runCatching { provider.closeWebAccess() }
            .onFailure { SafeLog.w(TAG, "could not release the web route", it) }
    }

    /**
     * Belt and braces for the route.
     *
     * [closeWebUi] is how the screen is normally left, and it releases
     * immediately. This covers the teardown that never ran it — the developer
     * option "don't keep activities", a locale or density change, or the task
     * being finished — because each of those destroys the activity, and therefore
     * this view model, without the screen ever calling `onClose`. Leaving the
     * loopback port listening with nobody left to close it is the one outcome
     * that must not happen.
     */
    override fun onCleared() {
        releaseWebRoute()
        super.onCleared()
    }

    private fun describe(status: ProviderStatus, res: android.content.res.Resources): String = when (status) {
        is ProviderStatus.Running ->
            if (status.ipv4.isEmpty()) {
                res.getString(R.string.status_running_at_unknown)
            } else {
                res.getString(R.string.status_running_at, status.ipv4)
            }
        is ProviderStatus.Error ->
            res.getString(R.string.status_error_message, status.messageRes?.let { resolve(it, res) } ?: status.message)
        is ProviderStatus.Unavailable ->
            res.getString(R.string.status_unavailable_message, resolve(status.reason, res))
        is ProviderStatus.Starting -> res.getString(R.string.status_starting)
        is ProviderStatus.Stopped -> res.getString(R.string.status_stopped)
    }

    /** Resolves a [TextRef] outside a composable (diagnostics only). */
    private fun resolve(ref: TextRef, res: android.content.res.Resources): String =
        if (ref.args.isEmpty()) res.getString(ref.res) else res.getString(ref.res, *ref.args.toTypedArray())

    private fun persist(transform: (AppConfig) -> AppConfig) {
        viewModelScope.launch {
            runCatching { repository.update(transform) }
                .onFailure { SafeLog.e(TAG, "could not persist configuration", it) }
        }
    }

    companion object {
        private const val TAG = "SetupVM"

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SetupViewModel(container) as T
            }
    }
}
