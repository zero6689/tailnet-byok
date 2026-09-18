package io.github.zero6689.tailnetbyok.ui.setup

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.zero6689.tailnetbyok.R
import io.github.zero6689.tailnetbyok.core.text.TextRef
import io.github.zero6689.tailnetbyok.data.config.AppConfig
import io.github.zero6689.tailnetbyok.di.AppContainer
import io.github.zero6689.tailnetbyok.domain.AddressMessages
import io.github.zero6689.tailnetbyok.domain.ConnectionTester
import io.github.zero6689.tailnetbyok.domain.TailnetAddressPolicy
import io.github.zero6689.tailnetbyok.net.ProviderId
import io.github.zero6689.tailnetbyok.net.ProviderStatus
import io.github.zero6689.tailnetbyok.ui.theme.Danger
import io.github.zero6689.tailnetbyok.ui.theme.TailnetByokTheme
import io.github.zero6689.tailnetbyok.ui.theme.Warning
import io.github.zero6689.tailnetbyok.ui.web.WebScreen
import kotlinx.coroutines.delay

/**
 * The whole app, on one scrollable screen.
 *
 * # Why one screen rather than a wizard
 *
 * The configuration has four fields and one button. A wizard would add navigation
 * state, back-stack semantics, and a "where was I" problem, in exchange for
 * hiding three fields that fit comfortably on a phone. Keeping it on one screen
 * also means the "test connection" result can sit directly under the fields it
 * describes, which is the entire point of having a test button.
 *
 * The screen is ordered by the dependency between the parts: what to reach, how
 * to reach it, prove it works, then the diagnostics for when it does not.
 */
@Composable
fun SetupRoute(container: AppContainer) {
    // Named `vm`, not `viewModel`: a local called `viewModel` would shadow the
    // androidx `viewModel()` function imported below, and the resulting
    // overload-resolution error is a genuinely confusing one to read.
    val vm: SetupViewModel = viewModel(factory = SetupViewModel.factory(container))
    val state by vm.state.collectAsStateWithLifecycle()

    // Two screens, one at a time, and no back stack. The web screen replaces the
    // setup screen because there is nothing to restore underneath it — this view
    // model survives the swap, so closing the web screen returns to exactly the
    // fields the user left. It is only reachable when the user asked for it:
    // `webUrl` is set by `openWebUi` and by nothing else.
    val webUrl = state.webUrl
    if (webUrl == null) {
        SetupScreen(state = state, actions = vm.asActions())
    } else {
        // `reveal()` is the one place the URL becomes text, and it goes straight
        // into the WebView. See `net/ConnectivityProvider.kt`: this string may
        // carry the loopback proxy's session token, so it is never logged and
        // never shown.
        WebScreen(url = webUrl.reveal(), onClose = vm::closeWebUi)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    state: UiState,
    actions: SetupActions,
) {
    var keyVisible by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                // The app bar, the launcher label and the release page all use
                // the same brand name — `screen_title` and `app_name` carry the
                // same value so the two can never drift apart.
                title = { Text(stringResource(R.string.screen_title)) },
                actions = {
                    NodeStatusPill(state.providerStatus)
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { IntroCard(state = state, onAcknowledge = actions::acknowledgeSecurityModel) }

            state.banner?.let { banner ->
                item { BannerCard(banner = banner, onDismiss = actions::dismissBanner) }
            }

            item { TargetSection(state = state, actions = actions) }

            item { ProviderSection(state = state, actions = actions) }

            if (state.config.provider == ProviderId.EMBEDDED_TSNET) {
                item { CredentialSection(state = state, actions = actions, keyVisible = keyVisible) }
                item {
                    TextButton(onClick = { keyVisible = !keyVisible }) {
                        Icon(
                            imageVector = if (keyVisible) Icons.Filled.LockOpen else Icons.Filled.Lock,
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (keyVisible) R.string.key_hide else R.string.key_reveal))
                    }
                }
                item { NodeSection(state = state, actions = actions) }
            }

            item { TestSection(state = state, actions = actions) }

            item { WebUiSection(state = state, actions = actions) }

            // Shown even when empty: "no diagnostics" is itself information, and a
            // panel that only appears once something has gone wrong cannot be
            // refreshed on the way to finding out.
            item { DiagnosticsSection(state = state, onRefresh = actions::loadDiagnostics) }

            item { FooterNote() }
        }
    }
}

/** What the actions surface the screen needs, as an interface, so previews work. */
interface SetupActions {
    fun updateHost(value: String)
    fun updatePort(value: String)
    fun updateScheme(value: String)
    fun updatePath(value: String)
    fun updateControlUrl(value: String)
    fun updateNodeHostname(value: String)
    fun updateEphemeral(value: Boolean)
    fun updateKeyDraft(value: String)
    fun saveKey()
    fun forgetKey()
    fun selectProvider(id: ProviderId)
    fun runTest()
    fun openWebUi()
    fun loadDiagnostics()
    fun acknowledgeSecurityModel()
    fun dismissBanner()
}

/**
 * Adapts the view model to the screen's action interface.
 *
 * This exists so the screen depends on [SetupActions] rather than on
 * [SetupViewModel] — which is what lets every `@Preview` in this file render a
 * real screen with no view model, no container and no Android runtime.
 */
private fun SetupViewModel.asActions(): SetupActions = object : SetupActions {
    override fun updateHost(value: String) = this@asActions.updateHost(value)
    override fun updatePort(value: String) = this@asActions.updatePort(value)
    override fun updateScheme(value: String) = this@asActions.updateScheme(value)
    override fun updatePath(value: String) = this@asActions.updatePath(value)
    override fun updateControlUrl(value: String) = this@asActions.updateControlUrl(value)
    override fun updateNodeHostname(value: String) = this@asActions.updateNodeHostname(value)
    override fun updateEphemeral(value: Boolean) = this@asActions.updateEphemeral(value)
    override fun updateKeyDraft(value: String) = this@asActions.updateKeyDraft(value)
    override fun saveKey() = this@asActions.saveKey()
    override fun forgetKey() = this@asActions.forgetKey()
    override fun selectProvider(id: ProviderId) = this@asActions.selectProvider(id)
    override fun runTest() = this@asActions.runTest()
    override fun openWebUi() = this@asActions.openWebUi()
    override fun loadDiagnostics() = this@asActions.loadDiagnostics()
    override fun acknowledgeSecurityModel() = this@asActions.acknowledgeSecurityModel()
    override fun dismissBanner() = this@asActions.dismissBanner()
}

// ---------------------------------------------------------------------------
// Sections
// ---------------------------------------------------------------------------

@Composable
private fun IntroCard(state: UiState, onAcknowledge: () -> Unit) {
    if (state.config.acknowledgedSecurityModel) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Info, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.intro_title), fontWeight = FontWeight.SemiBold)
            }
            Text(
                stringResource(R.string.intro_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onAcknowledge) { Text(stringResource(R.string.intro_acknowledge)) }
        }
    }
}

@Composable
private fun BannerCard(banner: UiState.Banner, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (banner.isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(banner.message.resolve(), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.banner_dismiss)) }
        }
    }
}

@Composable
private fun TargetSection(state: UiState, actions: SetupActions) {
    SectionCard(R.string.section_target_title, R.string.section_target_subtitle) {
        OutlinedTextField(
            value = state.config.hostInput,
            onValueChange = actions::updateHost,
            label = { Text(stringResource(R.string.target_host_label)) },
            placeholder = { Text(stringResource(R.string.target_host_placeholder)) },
            singleLine = true,
            isError = state.addressVerdict is TailnetAddressPolicy.Verdict.Rejected,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )

        AddressVerdictLine(state.addressVerdict)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("http", "https").forEach { scheme ->
                FilterChip(
                    selected = state.config.scheme == scheme,
                    onClick = { actions.updateScheme(scheme) },
                    label = { Text(scheme) },
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.config.port.toString(),
                onValueChange = actions::updatePort,
                label = { Text(stringResource(R.string.target_port_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(120.dp),
            )
            OutlinedTextField(
                value = state.config.path,
                onValueChange = actions::updatePath,
                label = { Text(stringResource(R.string.target_path_label)) },
                placeholder = { Text(stringResource(R.string.target_path_placeholder)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * The inline verdict on the host field.
 *
 * This is the highest-value piece of UI in the app: it turns the three most
 * common mistakes — a pasted URL, a public IP, and a bare short name — into
 * something the user reads *before* pressing the button, rather than a
 * timeout they have to interpret.
 */
@Composable
private fun AddressVerdictLine(verdict: TailnetAddressPolicy.Verdict) {
    val (icon, tint, textRes, textArgs) = when (verdict) {
        is TailnetAddressPolicy.Verdict.Allowed ->
            Quad(Icons.Filled.CheckCircle, MaterialTheme.colorScheme.primary, null, emptyList())
        is TailnetAddressPolicy.Verdict.AllowedWithWarning ->
            Quad(
                Icons.Filled.Warning,
                Warning,
                AddressMessages.warning(verdict.warning),
                if (verdict.warning == TailnetAddressPolicy.Warning.DEFAULTED_PORT) {
                    listOf(AppConfig.DEFAULT_PORT)
                } else {
                    emptyList()
                },
            )
        is TailnetAddressPolicy.Verdict.Rejected ->
            Quad(Icons.Filled.Error, Danger, AddressMessages.reason(verdict.reason), emptyList())
    }
    if (textRes == null) return
    val text = if (textArgs.isEmpty()) {
        stringResource(textRes)
    } else {
        stringResource(textRes, *textArgs.toTypedArray())
    }
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.width(8.dp))
        Text(text, color = tint, style = MaterialTheme.typography.bodySmall)
    }
}

/** A four-slot tuple; Kotlin's stdlib stops at [Triple]. */
private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

@Composable
private fun ProviderSection(state: UiState, actions: SetupActions) {
    SectionCard(R.string.section_provider_title, R.string.section_provider_subtitle) {
        ProviderId.entries.forEach { id ->
            val unavailable = id == ProviderId.EMBEDDED_TSNET && state.embeddedUnavailableReason != null
            FilterChip(
                selected = state.config.provider == id,
                enabled = !unavailable,
                onClick = { actions.selectProvider(id) },
                label = { Text(stringResource(id.labelRes)) },
            )
            if (unavailable) {
                Text(
                    state.embeddedUnavailableReason!!.resolve(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state.config.provider == ProviderId.SYSTEM_NETWORK) {
            Text(
                stringResource(R.string.provider_system_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CredentialSection(
    state: UiState,
    actions: SetupActions,
    keyVisible: Boolean,
) {
    val clipboard = LocalClipboardManager.current

    SectionCard(R.string.section_key_title, R.string.section_key_subtitle) {
        if (state.config.hasStoredKey) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.key_stored), Modifier.weight(1f))
            }
            Text(
                stringResource(
                    if (state.hardwareBackedKeystore) {
                        R.string.key_hardware_backed
                    } else {
                        R.string.key_software_backed
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = actions::forgetKey) { Text(stringResource(R.string.key_forget)) }
        } else {
            OutlinedTextField(
                value = state.keyDraft,
                onValueChange = actions::updateKeyDraft,
                label = { Text(stringResource(R.string.key_field_label)) },
                singleLine = true,
                visualTransformation = if (keyVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = actions::saveKey,
                    enabled = state.canSaveKey,
                ) { Text(stringResource(R.string.key_save)) }
                AssistChip(
                    onClick = {
                        clipboard.getText()?.text?.let(actions::updateKeyDraft)
                    },
                    label = { Text(stringResource(R.string.key_paste)) },
                    leadingIcon = {
                        Icon(Icons.Filled.ContentPaste, contentDescription = null)
                    },
                )
            }
            Text(
                stringResource(R.string.key_create_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NodeSection(state: UiState, actions: SetupActions) {
    SectionCard(R.string.section_node_title, R.string.section_node_subtitle) {
        OutlinedTextField(
            value = state.config.nodeHostname,
            onValueChange = actions::updateNodeHostname,
            label = { Text(stringResource(R.string.node_hostname_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.config.controlUrl,
            onValueChange = actions::updateControlUrl,
            label = { Text(stringResource(R.string.node_control_label)) },
            placeholder = { Text(stringResource(R.string.node_control_placeholder)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.node_ephemeral))
                Text(
                    stringResource(R.string.node_ephemeral_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = state.config.ephemeral, onCheckedChange = actions::updateEphemeral)
        }
    }
}

@Composable
private fun TestSection(state: UiState, actions: SetupActions) {
    SectionCard(R.string.section_test_title, R.string.section_test_subtitle) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = actions::runTest,
                // Also held back while the DSH UI is being opened: that path
                // runs the test's own bring-up, and two bring-ups at once would
                // have the same provider starting twice.
                enabled = state.canRunTest && !state.isOpeningWebUi,
                modifier = Modifier.weight(1f),
            ) {
                if (state.isTesting) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp).width(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(R.string.test_run))
                }
            }
            OutlinedButton(onClick = actions::loadDiagnostics) {
                Text(stringResource(R.string.test_diagnostics_button))
            }
        }

        state.testBlockedReasonRes?.let {
            Text(
                stringResource(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.steps.isNotEmpty()) {
            HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                state.steps.forEach { StepRow(it) }
            }
        }

        state.report?.let { report ->
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (report.succeeded) Icons.Filled.CheckCircle else Icons.Filled.Error,
                    contentDescription = null,
                    tint = if (report.succeeded) MaterialTheme.colorScheme.primary else Danger,
                )
                Spacer(Modifier.width(8.dp))
                Text(report.summary().resolve(), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * The payoff of the whole screen: the target's own interface, in the app.
 *
 * The button is gated by [UiState.canOpenWebUi], which is the connection test's
 * own gate — the same target validation and the same credential requirement —
 * because opening the UI runs the same bring-up the test does.
 */
@Composable
private fun WebUiSection(state: UiState, actions: SetupActions) {
    SectionCard(R.string.section_web_title, R.string.section_web_subtitle) {
        Button(
            onClick = actions::openWebUi,
            enabled = state.canOpenWebUi,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.isOpeningWebUi) {
                CircularProgressIndicator(
                    modifier = Modifier.height(18.dp).width(18.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.web_opening))
            } else {
                Text(stringResource(R.string.btn_open_web_ui))
            }
        }

        if (!state.isOpeningWebUi) {
            state.testBlockedReasonRes?.let {
                Text(
                    stringResource(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StepRow(step: ConnectionTester.StepResult) {
    val tint = when (step.outcome) {
        ConnectionTester.Outcome.PASSED -> MaterialTheme.colorScheme.primary
        ConnectionTester.Outcome.WARNED -> Warning
        ConnectionTester.Outcome.FAILED -> Danger
        ConnectionTester.Outcome.SKIPPED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val icon = when (step.outcome) {
        ConnectionTester.Outcome.PASSED -> Icons.Filled.CheckCircle
        ConnectionTester.Outcome.WARNED -> Icons.Filled.Warning
        ConnectionTester.Outcome.FAILED -> Icons.Filled.Error
        ConnectionTester.Outcome.SKIPPED -> Icons.Filled.Info
    }
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(step.step.labelRes), style = MaterialTheme.typography.bodyMedium)
            Text(
                step.detail.resolve(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        step.elapsedMs?.let {
            Text(stringResource(R.string.step_elapsed_ms, it), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun DiagnosticsSection(state: UiState, onRefresh: () -> Unit) {
    // The panel exists to be pasted into a bug report, so "copy" is the action
    // that matters and it is done here rather than in the view model: the text is
    // already in the state, and the clipboard is a UI concern.
    //
    // A section rather than a screen, deliberately. This app has no navigation
    // library (see docs/ARCHITECTURE.md), and a second full screen for a block of
    // text would mean introducing one, or a flag in the view model that pretends
    // to be one.
    val clipboard = LocalClipboardManager.current
    val copiedLabel = stringResource(R.string.diag_copied)
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }

    SectionCard(R.string.section_diagnostics_title, R.string.section_diagnostics_subtitle) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.diag_refresh))
            }
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(state.diagnostics.joinToString("\n")))
                    copied = true
                },
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.diag_copy))
            }
            if (copied) {
                Spacer(Modifier.width(8.dp))
                Text(
                    copiedLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (state.diagnostics.isEmpty()) {
            Text(
                stringResource(R.string.diag_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // Selectable as well as copyable: someone reading this on a desktop
            // beside the phone will want to take half of it, and the copy button
            // only takes all of it.
            SelectionContainer {
                Text(
                    state.diagnostics.joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun FooterNote() {
    Column(Modifier.padding(vertical = 16.dp)) {
        Text(
            stringResource(R.string.footer_privacy),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        // The trademark disclaimer, in the app rather than only in the repository.
        //
        // Most people who install this will never open the README, and "unofficial
        // client" is exactly the fact a user should not have to go looking for. It
        // is also the fact a rights holder is entitled to have stated.
        Text(
            stringResource(R.string.footer_trademark),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NodeStatusPill(status: ProviderStatus?) {
    val (labelRes, tint) = when (status) {
        is ProviderStatus.Running -> R.string.status_up to MaterialTheme.colorScheme.primary
        is ProviderStatus.Starting -> R.string.status_starting to Warning
        is ProviderStatus.Error -> R.string.status_error to Danger
        is ProviderStatus.Unavailable -> R.string.status_off to MaterialTheme.colorScheme.onSurfaceVariant
        else -> R.string.status_idle to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        stringResource(labelRes),
        color = tint,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(end = 16.dp),
    )
}

@Composable
private fun SectionCard(
    @StringRes titleRes: Int,
    @StringRes subtitleRes: Int? = null,
    content: @Composable () -> Unit,
) {
    Card {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(titleRes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            subtitleRes?.let {
                Text(
                    stringResource(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
        }
    }
}

/** Shared no-op action set for previews: a preview must not touch real state. */
private val noopActions = object : SetupActions {
    override fun updateHost(value: String) = Unit
    override fun updatePort(value: String) = Unit
    override fun updateScheme(value: String) = Unit
    override fun updatePath(value: String) = Unit
    override fun updateControlUrl(value: String) = Unit
    override fun updateNodeHostname(value: String) = Unit
    override fun updateEphemeral(value: Boolean) = Unit
    override fun updateKeyDraft(value: String) = Unit
    override fun saveKey() = Unit
    override fun forgetKey() = Unit
    override fun selectProvider(id: ProviderId) = Unit
    override fun runTest() = Unit
    override fun openWebUi() = Unit
    override fun loadDiagnostics() = Unit
    override fun acknowledgeSecurityModel() = Unit
    override fun dismissBanner() = Unit
}

@Preview(showBackground = true, name = "Setup — empty")
@Composable
private fun PreviewEmpty() {
    TailnetByokTheme(dynamicColor = false) {
        SetupScreen(
            state = UiState(config = AppConfig(acknowledgedSecurityModel = true)),
            actions = noopActions,
        )
    }
}

@Preview(showBackground = true, name = "Setup — passed test")
@Composable
private fun PreviewPassed() {
    val steps = listOf(
        ConnectionTester.StepResult(
            ConnectionTester.Step.VALIDATE_ADDRESS,
            ConnectionTester.Outcome.PASSED,
            R.string.step_address_ok,
        ),
        ConnectionTester.StepResult(
            ConnectionTester.Step.START_NODE,
            ConnectionTester.Outcome.PASSED,
            R.string.step_node_up_in,
            "100.101.102.104",
            "phone.tailnet-name.ts.net",
        ),
        ConnectionTester.StepResult(
            ConnectionTester.Step.PROBE_TCP,
            ConnectionTester.Outcome.PASSED,
            TextRef.of(R.string.step_tcp_connected, 142L),
            142,
        ),
        ConnectionTester.StepResult(
            ConnectionTester.Step.PROBE_HTTP,
            ConnectionTester.Outcome.PASSED,
            TextRef.of(R.string.step_http_result, 200, 187L),
            187,
        ),
    )
    TailnetByokTheme(dynamicColor = false) {
        SetupScreen(
            state = UiState(
                config = AppConfig(
                    hostInput = "100.101.102.103",
                    hasStoredKey = true,
                    acknowledgedSecurityModel = true,
                ),
                steps = steps,
                report = ConnectionTester.Report(
                    steps = steps,
                    succeeded = true,
                    targetUrl = "http://100.101.102.103:3080/",
                    resolvedAddress = "100.101.102.103",
                ),
            ),
            actions = noopActions,
        )
    }
}

@Preview(showBackground = true, name = "Setup — rejected address")
@Composable
private fun PreviewRejected() {
    TailnetByokTheme(dynamicColor = false) {
        SetupScreen(
            state = UiState(
                config = AppConfig(
                    hostInput = "http://example.com",
                    acknowledgedSecurityModel = true,
                ),
            ),
            actions = noopActions,
        )
    }
}
