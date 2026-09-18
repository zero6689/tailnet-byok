package io.github.zero6689.tailnetbyok.ui.web

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.zero6689.tailnetbyok.R
import io.github.zero6689.tailnetbyok.core.log.SafeLog

/**
 * The target's own web interface, in a WebView.
 *
 * # What this file is responsible for, and what it is not
 *
 * It owns the WebView: its settings, its load states, the system back gesture,
 * the file-upload bridge the DSH page needs, and the teardown that must happen
 * where the WebView was created.
 *
 * It knows nothing about how [url] was obtained. On the embedded-node route that
 * URL is a loopback reverse proxy carrying a session token; on the system-network
 * route it is the target itself. Both are just a URL to this screen, which is the
 * point: [url] may be a capability, so it is never logged, never shown and never
 * kept once the screen is gone. For the same reason the release of that route is
 * not here either — `onClose` is how this screen asks to be left, and the caller
 * that acquired the route releases it. See `SetupViewModel.closeWebUi` and
 * `TsnetConnectivityProvider.closeWebAccess`.
 *
 * # Why the Java-style WebView API rather than a Compose browser
 *
 * There is no `androidx.webkit` dependency in this project, and this is the only
 * WebView in it, so the plain `android.webkit.*` API is the whole requirement —
 * no artifact to download, no second way of doing the same thing.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebScreen(
    url: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Held as MutableState objects rather than `by` delegates because the clients
    // below are built once, inside `remember`, and capture them: a captured
    // MutableState is a stable reference to the same cell for the life of the
    // screen, which is exactly what a callback that outlives a recomposition
    // needs.
    val load = remember { mutableStateOf<LoadState>(LoadState.Loading) }
    val webView = remember { mutableStateOf<WebView?>(null) }
    val pendingFileChooser = remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        // Cancelling the picker answers with null, which is the "user chose
        // nothing" result the page is waiting for. Either way the callback is
        // answered exactly once — see [answerFileChooser].
        answerFileChooser(pendingFileChooser, uri?.let { arrayOf(it) })
    }

    val chrome = remember {
        object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: WebChromeClient.FileChooserParams?,
            ): Boolean {
                // The page asked again before the previous request was answered.
                // That older request can never be answered now, so it is released
                // rather than left hanging, and the reference is replaced.
                answerFileChooser(pendingFileChooser, null)
                if (filePathCallback == null) return false
                pendingFileChooser.value = filePathCallback

                return try {
                    // The Storage Access Framework: the app never sees a path and
                    // holds no file permission — the user grants one document.
                    filePicker.launch(acceptedMimeType(fileChooserParams))
                    true
                } catch (e: ActivityNotFoundException) {
                    // A device with no document picker is rare, a hung file input
                    // is not recoverable. Answering null is what unblocks the page.
                    answerFileChooser(pendingFileChooser, null)
                    SafeLog.w(TAG, "no activity can return content for the page's file input", e)
                    true
                }
            }
        }
    }

    val client = remember {
        object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                load.value = LoadState.Loading
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // A failed page still "finishes". The failure is the more useful
                // thing to keep on screen, so it wins.
                if (load.value is LoadState.Loading) load.value = LoadState.Ready
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                // Subresource failures — an icon, a font, one aborted fetch — are
                // not the page failing. The DSH UI holds an event stream open and
                // makes them routine, so only the main frame counts.
                if (request?.isForMainFrame != true) return
                load.value = LoadState.Failed(
                    statusCode = 0,
                    detail = error?.description?.toString().orEmpty(),
                )
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?,
            ) {
                if (request?.isForMainFrame != true) return
                val status = errorResponse?.statusCode ?: 0
                if (status in 200..299) return
                // The reason this hook exists: the loopback proxy answers a
                // failed upstream dial with 502 and a plain-text body, and
                // without this the user would be shown that body as if it were
                // the interface.
                load.value = LoadState.Failed(statusCode = status, detail = "")
            }
        }
    }

    val retry: () -> Unit = {
        load.value = LoadState.Loading
        webView.value?.loadUrl(url)
    }

    BackHandler(enabled = true) {
        // Back means "up one page" while there is history to go up, which is what
        // a browser-shaped screen has trained the user to expect. At the start of
        // history it means "leave", which is what `onClose` does.
        val view = webView.value
        if (view != null && view.canGoBack()) view.goBack() else onClose()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.web_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.web_close),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    // Not a build flag, and not conditional on BuildConfig.DEBUG:
                    // a debuggable WebContents is a socket anything on the device
                    // can attach to, and this one renders a page authenticated
                    // with the user's own session.
                    WebView.setWebContentsDebuggingEnabled(false)

                    // The DSH login handshake is a cookie handshake: 303 with
                    // Set-Cookie, then a request that must carry it. Cookies are
                    // first-party here — the page and its API calls share one
                    // origin — so third-party cookies stay off.
                    CookieManager.getInstance().setAcceptCookie(true)

                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // Explicit setter calls rather than the `webViewClient =`
                        // property form: those synthetic properties come from
                        // `getWebViewClient()`/`getWebChromeClient()`, which only
                        // appear in API 26+ — fine for minSdk 26, but there is no
                        // reason to depend on that for a line this short.
                        setWebViewClient(client)
                        setWebChromeClient(chrome)
                        webView.value = this
                        loadUrl(url)
                    }
                },
            )

            when (val state = load.value) {
                LoadState.Loading -> LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                )

                LoadState.Ready -> Unit

                is LoadState.Failed -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    LoadFailedCard(failure = state, onRetry = retry, onClose = onClose)
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // A file chooser still open here can never be answered, and an
            // unanswered callback leaves the page's input spinning forever.
            answerFileChooser(pendingFileChooser, null)

            // The target's session cookie is a credential, and Android's WebView
            // jar persists it to disk: leaving the screen must abandon the
            // session. Web Storage is deliberately left alone — the DSH page
            // keeps UI preferences there, wiping them on every visit would be a
            // regression, and the cookie is the credential, not the preferences.
            // Nothing is lost by dropping the cookie: the login handshake
            // repeats by itself on the next visit. The removal is asynchronous;
            // the process keeps the promise in the app-private store.
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()

            webView.value?.let { view ->
                // Detach before destroying: a WebView destroyed while it is still
                // attached to a parent is a known crash, and this screen is
                // disposed in the middle of a recomposition.
                (view.parent as? ViewGroup)?.removeView(view)
                view.stopLoading()
                view.destroy()
            }
            webView.value = null

            // The route the page was loaded from is not released here, on
            // purpose: this screen has no idea whether there is a proxy behind
            // the URL. The caller releases it — see the note at the top of this
            // file.
        }
    }
}

/**
 * Where the page load has got to.
 *
 * [Failed.statusCode] is 0 for a transport-level failure and the HTTP status for
 * a response the page should not be showing, which is how the 502 the proxy
 * answers a failed dial with arrives here.
 */
private sealed interface LoadState {
    data object Loading : LoadState
    data object Ready : LoadState
    data class Failed(val statusCode: Int, val detail: String) : LoadState
}

/**
 * The load failure, with the two things the user can actually do about it.
 *
 * Retry reloads the same URL: the proxy the page came from is still listening,
 * and a 502 in particular is usually a target that has just come up. Close is
 * the other honest answer, and it releases the route — opening the screen again
 * acquires a fresh one.
 */
@Composable
private fun LoadFailedCard(
    failure: LoadState.Failed,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Error, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.web_error_title),
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Text(
                text = if (failure.statusCode != 0) {
                    stringResource(R.string.web_error_http, failure.statusCode)
                } else {
                    // The WebView's own description is diagnostic text, passed
                    // through verbatim inside a translated frame — the same rule
                    // the native layers follow.
                    stringResource(
                        R.string.web_error_network,
                        failure.detail.ifEmpty { "no detail reported by the WebView" },
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRetry) { Text(stringResource(R.string.web_retry)) }
                TextButton(onClick = onClose) { Text(stringResource(R.string.web_close)) }
            }
        }
    }
}

/**
 * Answers a pending file chooser exactly once.
 *
 * The callback is removed from [pending] *before* it is invoked, so no path
 * through this composable can hand the native side two results for one request:
 * a pick arriving after a cancel finds nothing to answer, and a second request
 * cannot reach a callback that has already been answered. Called from the UI
 * thread only, which is where `onReceiveValue` has to run.
 */
private fun answerFileChooser(
    pending: MutableState<ValueCallback<Array<Uri>>?>,
    uris: Array<Uri>?,
) {
    val callback = pending.value ?: return
    pending.value = null
    callback.onReceiveValue(uris)
}

/**
 * The MIME type to hand the document picker.
 *
 * The wildcard type unless the page named a real MIME type. Extension-only hints
 * (`accept=".csv"`) are dropped rather than passed through: `GetContent` takes a
 * MIME type, and an intent whose type is `.csv` matches no provider at all —
 * which the user experiences as a picker that opens with nothing in it.
 */
private fun acceptedMimeType(params: WebChromeClient.FileChooserParams?): String {
    val acceptTypes: Array<out String>? = params?.acceptTypes
    return acceptTypes?.firstOrNull { it.contains('/') } ?: "*/*"
}

private const val TAG = "WebScreen"
