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
import io.github.zero6689.tailnetbyok.domain.ScannedText
import io.github.zero6689.tailnetbyok.domain.SetupLink
import io.github.zero6689.tailnetbyok.domain.SetupLinkParse
import io.github.zero6689.tailnetbyok.domain.SetupLinkParser
import io.github.zero6689.tailnetbyok.domain.SetupLinkRejection
import io.github.zero6689.tailnetbyok.domain.TailnetAddressPolicy
import io.github.zero6689.tailnetbyok.domain.TurnNoticeOutcome
import io.github.zero6689.tailnetbyok.domain.TurnWatch
import io.github.zero6689.tailnetbyok.domain.UpdateChecker
import io.github.zero6689.tailnetbyok.domain.UpdateFailure
import io.github.zero6689.tailnetbyok.domain.UpdateInstaller
import io.github.zero6689.tailnetbyok.domain.UpdateOutcome
import io.github.zero6689.tailnetbyok.domain.UpdateProtocol
import io.github.zero6689.tailnetbyok.net.ConnectivityProvider
import io.github.zero6689.tailnetbyok.net.ProviderId
import io.github.zero6689.tailnetbyok.net.ProviderRegistry
import io.github.zero6689.tailnetbyok.net.ProviderStatus
import io.github.zero6689.tailnetbyok.net.WebAccess
import io.github.zero6689.tailnetbyok.net.WebUrl
import io.github.zero6689.tailnetbyok.notify.TurnChannelAdvice
import io.github.zero6689.tailnetbyok.notify.TurnNotifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    /**
     * True while the QR scanner has the screen.
     *
     * A flag rather than a separate navigation graph, for the same reason the DSH
     * UI is a flag: there is exactly one other screen, it has one way out, and
     * this view model survives the swap — so what the scanner found is already in
     * [pendingSetup] by the time the settings screen is drawn again.
     */
    val scanning: Boolean = false,
    val steps: List<ConnectionTester.StepResult> = emptyList(),
    val report: ConnectionTester.Report? = null,
    val providerStatus: ProviderStatus? = null,
    val diagnostics: List<String> = emptyList(),
    val hardwareBackedKeystore: Boolean = false,
    /** True while an update source is being queried and its package downloaded. */
    val isCheckingUpdate: Boolean = false,
    /**
     * The last update attempt, as it was left on disk.
     *
     * Held in the state rather than only fetched when the panel is drawn, because
     * the successful path ends with the system installer stopping this process:
     * the record has to be there on the next launch, and it has to be visible
     * without the user having to ask for diagnostics.
     */
    val updateOutcome: UpdateOutcome = UpdateOutcome.Unknown,
    /**
     * A version the update source advertised when the app checked by itself.
     *
     * Only the version file is read for this (see
     * [UpdateChecker.checkVersionOnly]): a newer build is worth *saying*, and not
     * worth downloading until the user agrees. Nothing is shown when the check
     * could not be made — a silent check that fails is not news.
     */
    val availableUpdate: String? = null,
    /**
     * The page a deployment points at for generating a configuration link or QR
     * code, when this build was given one.
     *
     * Empty in the public build: the mechanism ships, the value comes from the
     * deployment. See `docs/PROVISIONING.md`.
     */
    val provisioningUrl: String = BuildConfig.DEFAULT_PROVISIONING_URL,
    /** True when Android is waiting for the user to allow installs from this app. */
    val needsInstallPermission: Boolean = false,
    /**
     * A configuration link that arrived from outside and has not been decided on.
     *
     * It sits here — parsed, shown, *not applied* — because that is the whole
     * security model of the feature: a link can be printed by anyone, so the user
     * sees where it points before anything changes. See `domain/SetupLink.kt`.
     */
    val pendingSetup: SetupLink? = null,
    /** Why the last configuration link was refused, if it was. */
    val setupLinkRejection: SetupLinkRejection? = null,
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

    /**
     * Whether a launch should walk straight into the DSH screen.
     *
     * The DSH screen *is* this app; the settings page is where you go when
     * something has to change. Landing on the settings page every time means the
     * common case — a configured phone that just wants its session list — costs a
     * scroll and a tap, and the user rightly reads that as the app forgetting what
     * they set up.
     *
     * Deliberately not automatic in these cases:
     *
     *  * nothing configured yet, or a target the address policy rejects — there is
     *    nothing to open, and the fields are the point;
     *  * no credential on the embedded route — [canOpenWebUi] already says so, and
     *    the key field is where the user needs to be;
     *  * a configuration link arrived: it is untrusted input awaiting a decision,
     *    and opening a screen on top of that decision would hide the one control
     *    that makes links safe. Scanning has the same rule ([scanning]).
     */
    val shouldOpenWebUiOnLaunch: Boolean
        get() = webUrl == null &&
            !scanning &&
            !isOpeningWebUi &&
            pendingSetup == null &&
            setupLinkRejection == null &&
            canOpenWebUi

    /**
     * Whether "Check for updates" should be tappable.
     *
     * Built on [canRunTest] for the same reason [canOpenWebUi] is: reaching an
     * update source runs the same bring-up as a connection test — the address has
     * to be acceptable and, on the embedded route, the node has to be startable —
     * and a second copy of that predicate is how the two drift apart. A
     * half-typed update source disables it too, rather than letting the user start
     * a download that cannot be addressed.
     */
    val canCheckForUpdate: Boolean
        get() = canRunTest && !isCheckingUpdate && updateSourceErrorRes == null

    /**
     * The inline complaint about the update source field, or null when it is
     * empty (which means "use the target origin") or a usable absolute URL.
     */
    val updateSourceErrorRes: Int?
        get() {
            val raw = config.updateUrl.trim()
            if (raw.isEmpty()) return null
            return if (raw.startsWith("http://") || raw.startsWith("https://")) {
                null
            } else {
                R.string.update_base_invalid
            }
        }

    /**
     * The configuration the [pendingSetup] link would produce, for display and for
     * the address check — the same `TailnetAddressPolicy` question the live
     * configuration is asked, so a link cannot talk the app into a target the user
     * could not have typed themselves.
     */
    val pendingSetupConfig: AppConfig?
        get() = pendingSetup?.applyTo(config)

    /**
     * This configuration as a link another device can scan, or null when there is
     * nothing to hand over yet.
     *
     * Derived rather than stored: it is a pure function of [config], and the
     * writer already refuses to produce a link that does not parse back into the
     * configuration it came from (see `SetupLinkParser.format`). A stored copy
     * would be a second thing that can be out of date with the fields above it.
     */
    val setupLinkForSharing: String?
        get() = SetupLinkParser.format(config)

    val pendingSetupVerdict: TailnetAddressPolicy.Verdict?
        get() = pendingSetupConfig?.let {
            TailnetAddressPolicy.classify(it.hostInput, it.port, it.scheme)
        }

    /** Whether "Apply" should be tappable: a link the address policy rejects is not. */
    val canApplyPendingSetup: Boolean
        get() = pendingSetupVerdict != null &&
            pendingSetupVerdict !is TailnetAddressPolicy.Verdict.Rejected

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

    /** The start-up update check runs at most once per process; see [autoCheckForUpdate]. */
    private var autoCheckedUpdate = false

    /** The launch walk into the DSH screen also happens at most once; see [autoOpenWebUiIfConfigured]. */
    private var autoOpenedWebUi = false

    /**
     * Whether any configuration link has been offered in this process.
     *
     * A link is a decision waiting to be made, so it suppresses the automatic walk
     * into the DSH screen for the rest of the process — including after the link
     * has been applied or ignored, because the user is at that point deliberately
     * on the settings screen.
     */
    private var sawSetupLink = false

    /** The session poller, alive only while the DSH screen is; see [startTurnWatch]. */
    private var turnWatchJob: Job? = null
    private var turnWatch = TurnWatch()

    /**
     * What the poller has observed, for the diagnostics panel.
     *
     * Mutable state behind the immutable snapshot: none of it belongs in `UiState`
     * (nothing renders it except the diagnostics, and it changes every few seconds,
     * which would recompose the whole screen for it).
     */
    private data class WatchStatus(
        val polling: Boolean = false,
        val running: Int = 0,
        val lastAnswerAt: Long = 0L,
        val answers: Int = 0,
        val unusable: Int = 0,
        val lastFinishedTitle: String? = null,
        val lastFinishedAt: Long = 0L,
        val lastOutcome: TurnNoticeOutcome? = null,
        /** Whether the foreground service that keeps the poll alive was raised. */
        val serviceUp: Boolean = false,
        /** True when the platform refused to raise it (background start, no exemption). */
        val serviceRefused: Boolean = false,
    )

    private var watch = WatchStatus()

    /**
     * Watches for a session stopping, while the DSH screen is open.
     *
     * Polling rather than subscribing: the gateway's event stream is the page's own
     * private channel, while `session/list` is an endpoint that has survived
     * several DSH releases, and what is being detected is a *transition* — there is
     * nothing to react to during a turn, only to its end.
     *
     * The interval backs off while nothing is running. A poll every few seconds is
     * worth its battery only when there is something that can finish; when the
     * target is idle this is a heartbeat, not a watch.
     */
    private fun startTurnWatch(baseUrl: String) {
        turnWatchJob?.cancel()
        turnWatch = TurnWatch()
        // The service first, while the screen is still visible: Android refuses to
        // start a foreground service from the background, so this is the only moment
        // it can be raised for the session. It is what keeps the poll alive once the
        // user leaves the app — without it the process is frozen and a finished task
        // is never noticed. See notify/TurnWatchService.kt.
        val serviceUp = container.startWatchService()
        // And the channel the notice will be posted to, created now rather than at the
        // first finish: its importance is fixed at creation, so the diagnostics panel
        // can answer "would a notice banner?" before anything has finished.
        container.ensureTurnChannel()
        watch = WatchStatus(polling = true, serviceUp = serviceUp, serviceRefused = !serviceUp)
        turnWatchJob = viewModelScope.launch {
            val watcher = container.sessionWatcher(baseUrl)
            while (isActive) {
                val sessions = watcher.sessions()
                if (sessions != null) {
                    watch = watch.copy(
                        lastAnswerAt = System.currentTimeMillis(),
                        answers = watch.answers + 1,
                        running = sessions.count { it.running },
                    )
                    turnWatch.observe(sessions).forEach { finished ->
                        val outcome = container.notifyTurnFinished(finished.title)
                        watch = watch.copy(
                            lastFinishedTitle = finished.title,
                            lastFinishedAt = System.currentTimeMillis(),
                            lastOutcome = outcome,
                        )
                    }
                } else {
                    watch = watch.copy(unusable = watch.unusable + 1)
                }
                delay(if (sessions?.any { it.running } == true) TURN_POLL_BUSY_MS else TURN_POLL_IDLE_MS)
            }
        }
    }

    private fun stopTurnWatch() {
        turnWatchJob?.cancel()
        turnWatchJob = null
        container.stopWatchService()
        watch = watch.copy(polling = false, running = 0, serviceUp = false)
    }

    /**
     * What the poller has been doing, for the diagnostics panel.
     *
     * The reason this exists: when a "task finished" notification does not arrive,
     * the user has no way to tell whether the app was watching and declined, or was
     * not watching at all — and on Android those look identical from the outside.
     * Everything the loop knows is therefore on one line the user can read and paste.
     */
    private fun describeWatch(res: android.content.res.Resources): String {
        val status = when {
            !watch.polling -> res.getString(R.string.diag_watch_stopped)
            watch.lastAnswerAt == 0L -> res.getString(R.string.diag_watch_no_answer)
            watch.running > 0 -> res.getString(
                R.string.diag_watch_running,
                watch.running,
                ((System.currentTimeMillis() - watch.lastAnswerAt) / 1000).toString(),
            )
            else -> res.getString(
                R.string.diag_watch_idle,
                ((System.currentTimeMillis() - watch.lastAnswerAt) / 1000).toString(),
            )
        }
        // The service is the difference between "polling" and "polling while the app
        // is off screen", so whether it is up belongs on this line: without it, the
        // poll stops as soon as Android freezes the process, and that is invisible
        // from the page.
        val service = when {
            watch.serviceUp -> res.getString(R.string.diag_watch_service_up)
            watch.serviceRefused -> res.getString(R.string.diag_watch_service_refused)
            else -> res.getString(R.string.diag_watch_service_none)
        }
        return res.getString(R.string.diag_turn_watch, status, service)
    }

    private fun describeLastNotice(res: android.content.res.Resources): String {
        val outcome = watch.lastOutcome ?: return res.getString(R.string.diag_notice_none)
        val word = when (outcome) {
            TurnNoticeOutcome.POSTED -> res.getString(
                R.string.diag_notice_posted,
                // A diagnostics line, read once in a bug report: the clock time of
                // the post is what matters, not the locale's idea of it, and a
                // fixed format keeps the line comparable between two reports.
                SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(watch.lastFinishedAt)),
                watch.lastFinishedTitle?.takeIf { it.isNotBlank() }
                    ?: res.getString(R.string.notify_turn_fallback),
            )
            TurnNoticeOutcome.SKIPPED_IN_FOREGROUND -> res.getString(R.string.diag_notice_skipped_foreground)
            TurnNoticeOutcome.SKIPPED_NOTIFICATIONS_OFF -> res.getString(R.string.diag_notice_skipped_off)
            TurnNoticeOutcome.SKIPPED_NO_PERMISSION -> res.getString(R.string.diag_notice_skipped_permission)
            TurnNoticeOutcome.FAILED -> res.getString(R.string.diag_notice_failed)
        }
        return res.getString(R.string.diag_last_notice, word)
    }

    private fun describeNotifications(res: android.content.res.Resources): String {
        val word = when {
            !container.notificationPermissionGranted() -> res.getString(R.string.diag_notifications_denied)
            !container.notificationsEnabled() -> res.getString(R.string.diag_notifications_blocked)
            else -> res.getString(R.string.diag_notifications_allowed)
        }
        return res.getString(R.string.diag_notifications, word)
    }

    /**
     * Whether the finish notice will *pop up*, as opposed to merely arriving.
     *
     * This exists because 0.3.5 shipped the notice on a channel whose importance
     * could never produce a heads-up banner: the notification did arrive, silently, in
     * the shade, and "it never pops up" was indistinguishable from "it never came".
     * The channel's importance is fixed when the channel is created (the app can lower
     * it, never raise it), so the value the *system* holds is read back here — if a
     * future device reports "default", that is a channel from an older build or a
     * setting the user changed, and neither is something code can fix from here.
     */
    private fun describeTurnChannel(res: android.content.res.Resources): String {
        val word = when (TurnNotifications.channelAdvice(container.turnChannelImportance())) {
            TurnChannelAdvice.BANNER -> res.getString(R.string.diag_channel_banner)
            TurnChannelAdvice.SHADE_ONLY -> res.getString(R.string.diag_channel_shade_only)
            TurnChannelAdvice.SILENT -> res.getString(R.string.diag_channel_silent)
            TurnChannelAdvice.MISSING -> res.getString(R.string.diag_channel_missing)
        }
        return res.getString(R.string.diag_channel, word)
    }

    /**
     * One silent update check per process, and never before a target exists.
     *
     * The app's promise is that it talks to nobody but the target the user
     * configured, so with no usable target there is nothing to ask — and asking a
     * source the user never chose is exactly what this app does not do. Once per
     * process is enough: the answer cannot change while the process lives, and
     * re-checking on every configuration change would turn typing into requests.
     *
     * Failure is deliberately silent. A check nobody asked for must never be the
     * reason an error appears on a screen where nothing is wrong; [checkForUpdate]
     * is the one that reports, because that is the one the user pressed.
     */
    private fun autoCheckForUpdate(config: AppConfig) {
        if (autoCheckedUpdate) return
        val current = _state.value
        if (!current.canCheckForUpdate) return
        autoCheckedUpdate = true

        viewModelScope.launch {
            val provider = container.provider(config.provider) ?: return@launch
            val report = container.tester.run(
                config = config,
                nodeStateDir = container.nodeStateDir,
                provider = provider,
                readCredentials = { readCredentials() },
                onStep = { },
                scope = ConnectionTester.Scope.BRING_UP,
            )
            if (!report.succeeded) return@launch

            // No package ceiling is passed: this path never fetches a package.
            val checker = UpdateChecker(fetch = { spec -> provider.fetch(spec) })
            val result = withContext(Dispatchers.IO) {
                checker.checkVersionOnly(config.updateBase, BuildConfig.VERSION_NAME)
            }
            when (result) {
                is UpdateChecker.VersionCheck.Newer ->
                    _state.value = _state.value.copy(availableUpdate = result.advertised)

                is UpdateChecker.VersionCheck.UpToDate ->
                    _state.value = _state.value.copy(availableUpdate = null)

                // Silent by design: see the note above.
                is UpdateChecker.VersionCheck.Failed -> Unit
            }
        }
    }

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
                autoCheckForUpdate(config)
                autoOpenWebUiIfConfigured()
            }
            .launchIn(viewModelScope)

        // The update record is read once at start-up and on every change. It is
        // the only way the outcome of a completed download survives: the package
        // installer kills this process, so "verified, waiting to install" has to
        // come back from disk rather than from memory.
        repository.lastUpdate
            .catch { e ->
                SafeLog.e(TAG, "update record flow failed", e)
                emit(UpdateOutcome.Unknown)
            }
            .onEach { outcome -> _state.value = _state.value.copy(updateOutcome = outcome) }
            .launchIn(viewModelScope)

        // Configuration links arrive whenever the operating system feels like it:
        // before this view model exists (a cold start from a scan) or hours into a
        // session (a tap on a link in a chat). Collecting a flow covers both, and
        // the activity does not have to know whether anyone is listening.
        container.setupLinks
            .filterNotNull()
            .onEach { raw -> onSetupLink(raw) }
            .launchIn(viewModelScope)
    }

    /**
     * A configuration link the user pasted into the first-run card.
     *
     * It goes through exactly the same door as a link that arrived from the
     * operating system — parsed, shown, applied only on a tap — because "the user
     * typed it" is not a reason to trust it. The confirmation step exists to make
     * the *target* something the user agrees to, and that argument does not care
     * where the text came from.
     */
    fun offerPastedLink(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return
        container.offerSetupLink(trimmed)
    }

    /** Shows the scanner, which owns the screen until it finds something or is closed. */
    fun openScanner() {
        _state.value = _state.value.copy(scanning = true)
    }

    /** Leaves the scanner without applying anything. The configuration is untouched. */
    fun closeScanner() {
        _state.value = _state.value.copy(scanning = false)
    }

    /**
     * A QR code was read. The text goes through [offerPastedLink], which is the
     * same door a pasted link uses.
     *
     * That is the whole security argument for building a scanner at all: it does
     * not get its own path into the configuration, it does not apply anything, and
     * the user still sees the target it names and taps Apply. [ScannedText] only
     * strips the wrapping a codec may have added around the link; what comes out
     * is still untrusted input from a stranger's screen.
     */
    fun onScanned(raw: String) {
        _state.value = _state.value.copy(scanning = false)
        val text = ScannedText.setupLinkIn(raw)
        if (text.isEmpty()) return
        offerPastedLink(text)
    }

    /**
     * Walks into the DSH screen once, on the first state that can support it.
     *
     * Once per process, and never after a configuration link has been offered:
     * the point is to skip a settings page the user does not need, not to fight
     * them for the screen. `closeWebUi` does not re-arm it — leaving the DSH
     * screen to change a setting and staying there is a normal thing to do — and
     * a fresh launch re-arms it, which is exactly the "open the app, be where I
     * was" behaviour this exists for.
     */
    private fun autoOpenWebUiIfConfigured() {
        if (autoOpenedWebUi || sawSetupLink) return
        // A link delivered by the launcher intent may not have reached the state
        // flow yet; the container's own flow knows, so it decides.
        if (container.setupLinks.value != null) return
        if (!_state.value.shouldOpenWebUiOnLaunch) return
        autoOpenedWebUi = true
        openWebUi()
    }

    /**
     * Takes a configuration link apart and puts it in front of the user.
     *
     * Nothing is applied here, and nothing is applied without
     * [applyPendingSetup]: a link is untrusted input, and the user confirming the
     * target it names is the control that makes the whole feature safe. The raw
     * string is cleared immediately so a configuration change cannot replay it.
     */
    private fun onSetupLink(raw: String) {
        sawSetupLink = true
        container.offerSetupLink(null)
        when (val result = SetupLinkParser.parse(raw)) {
            is SetupLinkParse.Parsed ->
                _state.value = _state.value.copy(
                    pendingSetup = result.link,
                    setupLinkRejection = null,
                    banner = null,
                )

            is SetupLinkParse.Rejected ->
                _state.value = _state.value.copy(
                    pendingSetup = null,
                    setupLinkRejection = result.reason,
                    banner = UiState.Banner(TextRef.of(result.reason.messageRes()), isError = true),
                )
        }
    }

    /** Applies the confirmed link, merging it onto the stored configuration. */
    fun applyPendingSetup() {
        val link = _state.value.pendingSetup ?: return
        if (!_state.value.canApplyPendingSetup) return
        persist { link.applyTo(it) }
        _state.value = _state.value.copy(
            pendingSetup = null,
            setupLinkRejection = null,
            banner = UiState.Banner(TextRef.of(R.string.setup_link_applied), isError = false),
        )
    }

    /** Throws the pending link away. The configuration is untouched. */
    fun discardPendingSetup() {
        _state.value = _state.value.copy(pendingSetup = null, setupLinkRejection = null)
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

    /**
     * The update source, stored as typed.
     *
     * Not trimmed to emptiness or normalised into a URL here: an empty value has a
     * defined meaning ("use the target origin"), and rewriting what the user typed
     * is how a field ends up disagreeing with what they see. Whether the value is
     * usable is a question for [UiState.updateSourceErrorRes].
     */
    fun updateUpdateUrl(value: String) = persist { it.copy(updateUrl = value.trim()) }


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
                    // The update source, and what the last attempt to use it
                    // produced. Both belong in a report: "the app says it is up to
                    // date" is a claim, and these are the two facts that let
                    // someone check it.
                    add(
                        res.getString(
                            R.string.diag_update_source,
                            // "(empty)" rather than a URL with no host in it, for the
                            // same reason the panel says "no target yet": before a
                            // host is typed there is nothing true to print.
                            Redact.hostLabel(_state.value.config.updateBase),
                        ),
                    )
                    add(
                        res.getString(
                            R.string.diag_update_result,
                            resolve(_state.value.updateOutcome.describe(), res),
                        ),
                    )
                    add(describeNotifications(res))
                    add(describeTurnChannel(res))
                    add(describeWatch(res))
                    add(describeLastNotice(res))
                    addAll(lines)
                },
            )
        }
    }

    fun dismissBanner() {
        _state.value = _state.value.copy(banner = null)
    }

    // -- Updates -------------------------------------------------------------

    /**
     * Asks the update source what it has, and downloads from it when that is
     * newer than this build.
     *
     * # Why the connection test runs first
     *
     * Fetching through the embedded node requires the node to be up, exactly as
     * the health check and the DSH UI do, and the four steps that bring it up
     * already exist in [ConnectionTester]. Running them here with
     * [ConnectionTester.Scope.BRING_UP] means one implementation of the bring-up
     * and — more usefully — one set of sentences for why it failed.
     *
     * # What the state ends up holding
     *
     * Not the package. The verified bytes go straight to the installer's staging
     * area inside this coroutine and are then dropped; what the state keeps is the
     * outcome (and the outcome is written to disk, because the install path stops
     * this process). Keeping 60 MB in a `StateFlow` would be a leak with a
     * spinner attached.
     */
    fun checkForUpdate() {
        val current = _state.value
        if (!current.canCheckForUpdate) return

        viewModelScope.launch {
            _state.value = _state.value.copy(
                isCheckingUpdate = true,
                needsInstallPermission = false,
                steps = emptyList(),
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
                // Nothing can be fetched through a transport that never came up.
                record(UpdateOutcome.Failed(UpdateFailure.TRANSPORT_UNAVAILABLE))
                _state.value = _state.value.copy(
                    isCheckingUpdate = false,
                    report = report,
                    banner = UiState.Banner(
                        report.firstFailure?.detail ?: TextRef.of(R.string.update_fail_transport_unavailable),
                        isError = true,
                    ),
                )
                return@launch
            }

            val checker = UpdateChecker(
                fetch = { spec -> provider.fetch(spec) },
                // The embedded route carries the body as base64 inside a JSON
                // string, so it can only stage a fraction of what the system
                // network route can. Asking for the wrong ceiling here would mean
                // an out-of-memory kill instead of a sentence.
                maxPackageBytes = if (current.config.provider == ProviderId.EMBEDDED_TSNET) {
                    UpdateProtocol.MAX_EMBEDDED_APK_BYTES
                } else {
                    UpdateProtocol.MAX_APK_BYTES
                },
            )
            val result = withContext(Dispatchers.IO) {
                checker.check(current.config.updateBase, BuildConfig.VERSION_NAME)
            }

            val outcome = when (result) {
                is UpdateChecker.UpdateCheck.UpToDate ->
                    UpdateOutcome.UpToDate(result.current, result.advertised)

                is UpdateChecker.UpdateCheck.Failed -> UpdateOutcome.Failed(result.reason)

                is UpdateChecker.UpdateCheck.Downloaded -> runCatching {
                    withContext(Dispatchers.IO) { container.updateInstaller.stage(result.bytes) }
                }.fold(
                    onSuccess = {
                        UpdateOutcome.Ready(result.current, result.available, result.sizeBytes)
                    },
                    onFailure = { e ->
                        // The bytes verified but would not land on disk. Reported as
                        // a download failure: from the user's side that is what it is.
                        SafeLog.e(TAG, "could not stage the downloaded package", e)
                        UpdateOutcome.Failed(UpdateFailure.DOWNLOAD_FAILED)
                    },
                )
            }

            record(outcome)
            _state.value = _state.value.copy(isCheckingUpdate = false, report = report)
        }
    }

    /**
     * Hands the staged, verified package to the system installer.
     *
     * Every outcome is named, including the two that are the user's to resolve:
     * Android wanting permission first, and the archive turning out to declare a
     * different application. Both are recorded as failures so the panel says what
     * happened on the next launch rather than silently forgetting.
     */
    fun installUpdate() {
        if (_state.value.updateOutcome !is UpdateOutcome.Ready) return

        when (val request = container.updateInstaller.requestInstall()) {
            UpdateInstaller.InstallRequest.Launched ->
                _state.value = _state.value.copy(
                    needsInstallPermission = false,
                    banner = UiState.Banner(
                        TextRef.of(R.string.update_handed_to_installer),
                        isError = false,
                    ),
                )

            UpdateInstaller.InstallRequest.NeedsPermission ->
                _state.value = _state.value.copy(
                    needsInstallPermission = true,
                    banner = UiState.Banner(
                        TextRef.of(R.string.update_fail_install_blocked),
                        isError = true,
                    ),
                )

            UpdateInstaller.InstallRequest.MissingFile -> fail(UpdateFailure.APK_GONE)

            is UpdateInstaller.InstallRequest.WrongPackage -> {
                record(UpdateOutcome.Failed(UpdateFailure.WRONG_PACKAGE))
                _state.value = _state.value.copy(
                    banner = UiState.Banner(
                        TextRef.of(R.string.update_fail_wrong_package, request.packageName ?: "?"),
                        isError = true,
                    ),
                )
            }

            UpdateInstaller.InstallRequest.NoHandler -> fail(UpdateFailure.NO_INSTALLER)
        }
    }

    /**
     * Opens the per-app "install unknown apps" screen.
     *
     * Only reachable from the panel, and only after Android itself said the
     * permission is missing — this takes the user out of the app, which is not
     * something to do unprompted.
     */
    fun allowInstallSource() {
        if (!container.updateInstaller.openInstallPermissionSettings()) {
            _state.value = _state.value.copy(
                banner = UiState.Banner(
                    TextRef.of(R.string.update_settings_unavailable),
                    isError = true,
                ),
            )
        }
    }

    private fun fail(reason: UpdateFailure) {
        record(UpdateOutcome.Failed(reason))
        _state.value = _state.value.copy(
            banner = UiState.Banner(TextRef.of(reason.messageRes()), isError = true),
        )
    }

    /**
     * Writes an outcome to disk.
     *
     * Fire-and-forget on purpose: nothing on screen depends on the write having
     * landed, and a failure to persist must not turn a successful download into
     * an error message.
     */
    private fun record(outcome: UpdateOutcome) {
        viewModelScope.launch {
            runCatching { repository.recordUpdate(outcome) }
                .onFailure { SafeLog.e(TAG, "could not record the update outcome", it) }
        }
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
                    // Only now is there a route to poll, and a page that can be
                    // finished with.
                    startTurnWatch(access.url.reveal())
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
        // The watcher polls the route that is about to be released, and the cookie
        // it was reading is dropped with the screen: it stops before either.
        stopTurnWatch()
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

        /**
         * How often the session watch polls.
         *
         * Fast while something is running, because that is when the user is
         * waiting on an answer they will not see until they come back; slow
         * otherwise, where the poll only exists to notice the next turn starting.
         */
        private const val TURN_POLL_BUSY_MS = 5_000L
        private const val TURN_POLL_IDLE_MS = 20_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SetupViewModel(container) as T
            }
    }
}
