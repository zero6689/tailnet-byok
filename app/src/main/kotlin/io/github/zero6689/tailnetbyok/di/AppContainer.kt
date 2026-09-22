package io.github.zero6689.tailnetbyok.di

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.zero6689.tailnetbyok.data.SessionWatcher
import io.github.zero6689.tailnetbyok.data.config.AppConfig
import io.github.zero6689.tailnetbyok.data.config.ConfigRepository
import io.github.zero6689.tailnetbyok.data.crypto.KeystoreSecretVault
import io.github.zero6689.tailnetbyok.domain.ConnectionTester
import io.github.zero6689.tailnetbyok.domain.TurnNoticeOutcome
import io.github.zero6689.tailnetbyok.domain.UpdateInstaller
import io.github.zero6689.tailnetbyok.net.ConnectivityProvider
import io.github.zero6689.tailnetbyok.net.ProviderId
import io.github.zero6689.tailnetbyok.net.ProviderRegistry
import io.github.zero6689.tailnetbyok.notify.TurnNotifications
import io.github.zero6689.tailnetbyok.notify.TurnWatchService

/**
 * Manual dependency wiring.
 *
 * # Why not Hilt
 *
 * A DI framework earns its place when there are many injection sites, many
 * scopes, and many contributors who would otherwise disagree about lifetimes.
 * This app has four objects, one scope (the application), and a deliberately
 * narrow surface. A framework here would add an annotation processor, a build
 * plugin, a generated component, and a second way to construct every object —
 * in exchange for removing four lines of wiring that a reader can follow in one
 * glance.
 *
 * That is a judgement, not a principle. If this app grows a background
 * component, multiple flavours, or a second screen that needs its own graph, the
 * trade flips and Hilt should replace this file. The point of keeping the wiring
 * in one class is that the swap is then a single-file change.
 *
 * # Lifetimes this file guarantees
 *
 *  * [vault], [configRepository], [tester] — one instance for the process. The
 *    vault in particular must not be recreated casually: each instance would be
 *    harmless (the Keystore key is addressed by alias) but the lazy key creation
 *    path is worth exercising once.
 *  * [provider] — one instance per [ProviderId], created on first request. The
 *    embedded provider owns a live Tailscale node, so two instances would mean
 *    two nodes fighting over one state directory.
 */
class AppContainer(private val appContext: Context) {

    /**
     * Raw configuration links handed to the app from outside, newest last.
     *
     * A flow rather than a callback because the two ends live on different clocks:
     * an intent can arrive before the screen's view model exists (a cold start from
     * a scan) or long after it does (a tap on a link while the app is open). The
     * view model collects this, so neither case needs the activity to know whether
     * anyone is listening yet.
     *
     * The string is the raw URI, not a parsed [io.github.zero6689.tailnetbyok.domain.SetupLink]:
     * parsing is a decision, and decisions belong with the state that shows them.
     * ([io.github.zero6689.tailnetbyok.MainActivity] is the only writer.)
     */
    private val _setupLinks = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val setupLinks: kotlinx.coroutines.flow.StateFlow<String?> = _setupLinks

    /** Offers a link for the screen to parse, show and confirm. Null clears it. */
    fun offerSetupLink(raw: String?) {
        _setupLinks.value = raw
    }

    /**
     * The configuration a link-less first run starts from.
     *
     * From `-PdefaultTarget` / `-PdefaultMode` at build time, empty in the default
     * build: this app ships with no server of its own, and the public build must
     * keep that true. A private or branded build can pre-fill a target so its users
     * have nothing to type — the mechanism lives upstream, the value does not.
     */
    val firstRunDefaults: AppConfig =
        io.github.zero6689.tailnetbyok.domain.SetupLinkParser
            .parseDefaults(
                target = io.github.zero6689.tailnetbyok.BuildConfig.DEFAULT_TARGET,
                mode = io.github.zero6689.tailnetbyok.BuildConfig.DEFAULT_MODE,
                updateUrl = io.github.zero6689.tailnetbyok.BuildConfig.DEFAULT_UPDATE_URL,
            )

    /**
     * The application context, exposed for the one job that legitimately needs
     * it outside a composable: resolving resource ids to text when building
     * *data* rather than UI (the diagnostics lines). It is the application
     * context, so holding it costs nothing and cannot leak an Activity.
     */
    val appResources: android.content.res.Resources get() = appContext.resources

    val vault: KeystoreSecretVault by lazy { KeystoreSecretVault() }

    val configRepository: ConfigRepository by lazy {
        ConfigRepository(appContext, vault, firstRunDefaults)
    }

    val tester: ConnectionTester by lazy { ConnectionTester() }

    /**
     * The poller that notices a session stopping, over the route the DSH screen uses.
     *
     * Built per route and never kept: the base URL is a capability — on the
     * embedded route it carries a loopback session token — and the cookie is read
     * from the WebView's own jar on every request rather than copied here, so there
     * is no second copy of a credential with a longer life than the screen.
     */
    fun sessionWatcher(baseUrl: String): SessionWatcher = SessionWatcher(
        baseUrl = baseUrl,
        cookie = { android.webkit.CookieManager.getInstance().getCookie(baseUrl) },
    )

    /** Posts "a task finished". The foreground check lives in [TurnNotifications]. */
    fun notifyTurnFinished(sessionTitle: String?): TurnNoticeOutcome =
        TurnNotifications.turnFinished(appContext, sessionTitle)

    /**
     * Whether Android would let this app post a notification right now.
     *
     * Read live rather than cached, and split into its two halves, because they have
     * different fixes: "the user turned notifications off for this app" is a trip
     * into Android's settings, while "the permission was never granted" is the
     * dialog this app shows when the DSH screen opens. A single boolean would send
     * someone to the wrong place — and this is the exact question behind a
     * notification that never arrived.
     */
    fun notificationsAllowed(): Boolean = notificationsEnabled() && notificationPermissionGranted()

    fun notificationsEnabled(): Boolean =
        NotificationManagerCompat.from(appContext).areNotificationsEnabled()

    fun notificationPermissionGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Creates the finish-notice channel, early.
     *
     * Called when the watch starts rather than only when a notice is posted, for two
     * reasons: the channel's importance is fixed at creation, so the sooner it exists
     * with the right one the better — and the diagnostics line can then say whether a
     * notice would banner *before* the user has to wait for a task to finish to find
     * out. Idempotent, and cheap.
     */
    fun ensureTurnChannel() {
        TurnNotifications.ensureChannel(appContext)
    }

    /**
     * The finish-notice channel's effective importance, as the system sees it.
     *
     * Null means "not created yet". Anything below `HIGH` means a notice will reach
     * the shade without ever popping up — which is a different bug report from
     * "nothing arrived at all", and this is what tells the two apart.
     */
    fun turnChannelImportance(): Int? = TurnNotifications.channelImportance(appContext)

    /**
     * Raises the watch service, and reports whether Android allowed it.
     *
     * A refusal is not an error the caller can fix — Android forbids starting a
     * foreground service from the background without an exemption — so it is
     * reported, recorded in the diagnostics, and the poll carries on unprotected.
     */
    fun startWatchService(): Boolean {
        val started = TurnWatchService.start(appContext)
        watchServiceRefused = !started
        return started
    }

    fun stopWatchService() {
        TurnWatchService.stop(appContext)
    }

    fun watchServiceRunning(): Boolean = TurnWatchService.running

    /** True when the last attempt to raise the service was refused by the platform. */
    var watchServiceRefused: Boolean = false
        private set

    /**
     * Stages a verified update and asks the system to install it.
     *
     * One instance for the process, like everything else here: it holds only the
     * application context, and its two pieces of state — the staging directory and
     * the FileProvider authority — are derived from that context rather than kept.
     */
    val updateInstaller: UpdateInstaller by lazy { UpdateInstaller(appContext) }

    /**
     * Where the embedded node keeps its state.
     *
     * `noBackupFilesDir` because this directory holds a private node key: it is
     * useless on another device and must never be swept into a backup.
     */
    val nodeStateDir: String
        get() = configRepository.nodeStateDir()

    /** The non-secret default for the control-plane field. Never a credential. */
    val defaultControlUrl: String =
        io.github.zero6689.tailnetbyok.BuildConfig.DEFAULT_CONTROL_URL
            .takeIf { it.isNotBlank() && !AppConfig.isHostedControlPlane(it) }
            ?: ""

    private val providers = LinkedHashMap<ProviderId, ConnectivityProvider>()

    /**
     * Returns the provider for [id], or null when this build cannot offer it.
     *
     * Null is a normal outcome, not an error: the embedded provider is absent
     * from any build without the native bridge, and that is the default build.
     * Callers surface [ProviderRegistry.failureReason] to explain why.
     */
    @Synchronized
    fun provider(id: ProviderId): ConnectivityProvider? =
        providers.getOrPut(id) { ProviderRegistry.create(id) ?: return null }

    /** Drops the cached provider. Use when the provider itself turned unusable. */
    @Synchronized
    fun releaseProvider(id: ProviderId) {
        providers.remove(id)
    }
}
