package io.github.zero6689.tailnetbyok.di

import android.content.Context
import io.github.zero6689.tailnetbyok.data.config.AppConfig
import io.github.zero6689.tailnetbyok.data.config.ConfigRepository
import io.github.zero6689.tailnetbyok.data.crypto.KeystoreSecretVault
import io.github.zero6689.tailnetbyok.domain.ConnectionTester
import io.github.zero6689.tailnetbyok.domain.UpdateInstaller
import io.github.zero6689.tailnetbyok.net.ConnectivityProvider
import io.github.zero6689.tailnetbyok.net.ProviderId
import io.github.zero6689.tailnetbyok.net.ProviderRegistry

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
