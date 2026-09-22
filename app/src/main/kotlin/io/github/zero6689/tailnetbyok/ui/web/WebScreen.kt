package io.github.zero6689.tailnetbyok.ui.web

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Parcelable
import android.provider.MediaStore
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import io.github.zero6689.tailnetbyok.R
import io.github.zero6689.tailnetbyok.core.log.SafeLog
import kotlinx.coroutines.delay
import io.github.zero6689.tailnetbyok.domain.FileUploadRequest
import io.github.zero6689.tailnetbyok.domain.UpdateInstaller

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
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Held as MutableState objects rather than `by` delegates because the clients
    // below are built once, inside `remember`, and capture them: a captured
    // MutableState is a stable reference to the same cell for the life of the
    // screen, which is exactly what a callback that outlives a recomposition
    // needs.
    val context = LocalContext.current
    val load = remember { mutableStateOf<LoadState>(LoadState.Loading) }
    val webView = remember { mutableStateOf<WebView?>(null) }
    val pendingFileChooser = remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    // The staged photo target for the request in flight, when that request offered
    // the camera. Held as a file *and* its URI: the file is what has to be cleaned
    // up when the user picks a document instead of taking a photo.
    val capture = remember { mutableStateOf<CaptureTarget?>(null) }
    val pickerTitle = stringResource(R.string.web_pick_file)

    // Android 13+ wants the user to agree before anything can be posted. This
    // screen is where background work starts, so it is where the question belongs;
    // it is asked once (a refusal is remembered by the system, and the app treats
    // "no permission" as "no notification" rather than as an error).
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        // Cancelling answers with a non-OK result, which is the "user chose
        // nothing" result the page is waiting for. Either way the callback is
        // answered exactly once — see [answerFileChooser].
        val uris = if (result.resultCode == Activity.RESULT_OK) urisFrom(result.data, capture.value?.uri) else null
        // A staged photo nobody used is ours to remove. The one the page did
        // receive stays until the screen goes away, because the WebView reads it
        // asynchronously and a delete-now would race that read.
        if (capture.value != null && uris?.any { it == capture.value?.uri } != true) {
            capture.value?.file?.delete()
        }
        capture.value = null
        answerFileChooser(pendingFileChooser, uris?.toTypedArray())
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

                val request = FileUploadRequest.of(
                    acceptTypes = fileChooserParams?.acceptTypes,
                    allowMultiple = fileChooserParams?.mode ==
                        WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE,
                    captureEnabled = fileChooserParams?.isCaptureEnabled == true,
                )

                return try {
                    // The Storage Access Framework: the app never sees a path and
                    // holds no file permission — the user grants one document.
                    val pick = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = request.pickerType
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, request.allowMultiple)
                        if (request.extraMimeTypes.isNotEmpty()) {
                            putExtra(Intent.EXTRA_MIME_TYPES, request.extraMimeTypes.toTypedArray())
                        }
                    }
                    // "Take a photo" rides in the same system sheet as an extra
                    // source, so the user answers one question instead of two. A
                    // device with no camera app simply never shows the entry.
                    val staged = if (request.offersCamera) newCaptureTarget(context) else null
                    capture.value = staged
                    val chooser = Intent.createChooser(pick, pickerTitle).apply {
                        // EXTRA_INITIAL_INTENTS rather than the three-argument
                        // createChooser: that overload is ambiguous in Kotlin
                        // (there is an IntentSender one with the same shape), and
                        // this extra is the same mechanism without the ambiguity.
                        if (staged != null) {
                            val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                                putExtra(MediaStore.EXTRA_OUTPUT, staged.uri)
                                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf<Parcelable>(cameraIntent))
                        }
                    }
                    filePicker.launch(chooser)
                    true
                } catch (e: ActivityNotFoundException) {
                    // A device with no document picker is rare, a hung file input
                    // is not recoverable. Answering null is what unblocks the page.
                    capture.value?.file?.delete()
                    capture.value = null
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
                // The page is the same page the shell renders, so the shell's own
                // page-level touches belong here too — see tunePage. Idempotent, and
                // re-run on every navigation because a reload replaces the document.
                view?.let { tunePage(it) }
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

    // The DSH screen is the app: its content gets every pixel the system does not need.
    // There is deliberately no app bar any more.
    //
    // It used to carry a `TopAppBar` with the title and two ways out, which cost a
    // full 56dp row above a page that already draws its own header — on a 729px-tall
    // phone that is 8% of the viewport spent on a label nobody needs ("DSH 界面",
    // when the page behind it says which screen you are on) and two icons that fit
    // in the strip the system already reserves.
    //
    // So: the WebView starts directly below the status bar, the one exit floats over
    // the page in a small translucent pill that gets out of the way by itself, and the
    // failure card keeps its own buttons for when the page did not load at all.
    //
    // # Why this app's own settings button is gone (0.3.8)
    //
    // 0.3.5 added a second floating control down in the sidebar column, so that "back
    // to this app's settings" was one thumb-reach away. It was a duplicate: it called
    // the same callback as the arrow above (`onOpenSettings` and `onClose` are both
    // `closeWebUi`, because the DSH screen is a child of the settings screen). And it
    // landed in the corner the *page's* own controls live in, so the bottom of the
    // sidebar showed two settings buttons — the page's, and ours — which is what the
    // user saw and asked to merge.
    //
    // Only one of the two could stay, and the page decides which: DSH renders its
    // settings trigger in exactly one place, the sidebar's own footer row
    // (`settings.trigger`, inside the `sidebar.settings` slot). Removing that row would
    // leave the user with no way to reach DSH's settings at all, while dropping ours
    // costs nothing that is not already on screen — the Back gesture leaves this screen
    // from anywhere, and the arrow above is the visible copy of the same action.
    //
    // The page's footer therefore keeps its own bottom, inside the safe area, with no
    // strip reserved for anything of ours: cost panel, then "设置" — one settings entry
    // at the bottom, and the one the user was pointing at.
    var controlsVisible by remember { mutableStateOf(true) }

    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                // The page gets every pixel the system does not need, and none of the
                // ones it does: nothing under the clock or the battery (the page draws
                // its own header, and it must not sit under them), and — this is the
                // 0.3.6 fix — nothing under the navigation bar either.
                //
                // Measured on the maintainer's phone (HONOR Play 9T, 720x1610 at
                // 320dpi, 3-button navigation), 2026-09-21: the page ran to the very
                // bottom edge, so the composer's tool row, the sidebar's own settings
                // row and the cost panel above it were drawn *under* the navigation
                // bar — visible through it and untappable, because the system bar takes
                // the touches. The screenshot shows the page's settings row dimmed to a
                // ghost, one row above the system buttons.
                //
                // `safeDrawing` is `systemBars + displayCutout + ime`. The union is the
                // point, not a sum: with the keyboard up, the IME frame is measured
                // from the window's bottom edge and already contains the navigation
                // bar, so adding the two would take that height twice and float the
                // composer a navigation bar above the keyboard.
                //
                // The IME half is the older fix (0.2.8) and is kept for its own
                // reason: this engine (Chromium 116 WebView on Android 14) sometimes
                // tells the page nothing about the keyboard at all — both
                // `innerHeight` and `visualViewport.height` keep the keyboard-closed
                // value — so no page-side patch can notice, and the shrink has to be
                // done where the inset is actually known.
                .windowInsetsPadding(WindowInsets.safeDrawing),
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
                    LoadFailedCard(
                        failure = state,
                        onRetry = retry,
                        onClose = onClose,
                        onOpenSettings = onOpenSettings,
                    )
                }
            }
        }

        // The way out, in the corner the platform puts it in. It used to be one of two
        // floating controls, the other being this app's own settings button; that one is
        // gone — see the note above `controlsVisible`.
        Box(
            Modifier
                .align(Alignment.TopStart)
                // `safeDrawing` rather than the status bar alone: in landscape the
                // cutout is on a side, and this pill is what leaves the screen.
                // Padding the other three sides costs nothing — the pill is aligned to
                // the top and the start, so it only ever moves down and inwards.
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(start = 6.dp, top = 4.dp),
        ) {
            if (controlsVisible) {
                Box(
                    Modifier
                        .size(40.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                            shape = RoundedCornerShape(20.dp),
                        )
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.web_back_to_settings),
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                // Hidden, but findable: a slim grip instead of nothing at all. The
                // system Back button and gesture still leave the screen, so this is
                // the visible second way, not the only one.
                Box(
                    Modifier
                        .size(width = 44.dp, height = 14.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp),
                        )
                        .clickable { controlsVisible = true },
                )
            }
        }
    }

    // Auto-hide, restarted whenever the load state changes: the way out is visible
    // again after a failure, because "where did the way out go" is a bad question to
    // leave someone asking on a screen that did not load.
    LaunchedEffect(controlsVisible, load.value) {
        if (controlsVisible) {
            delay(5000)
            controlsVisible = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // A file chooser still open here can never be answered, and an
            // unanswered callback leaves the page's input spinning forever.
            answerFileChooser(pendingFileChooser, null)

            // A staged photo that this screen still owns goes with it. The bytes
            // are already in the page's hands by then (it read them when the
            // upload ran), so this only reclaims the cache copy.
            capture.value?.file?.delete()
            capture.value = null

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
 * The page-level touches the sibling shell applies, ported so both render the same.
 *
 * The two apps load the *same* page from the same server, and the shell's
 * `tunePage()` deliberately injects almost nothing: the DSH UI has its own
 * breakpoints, and a wrapper that also rewrote the layout (collapsing grids,
 * forcing dialogs full-screen) fought them and produced squeezed columns. What the
 * shell does add is three small facts the page cannot state for itself, and this is
 * that same set:
 *
 *  * a `viewport` meta, **only if the page has none** — and with
 *    `viewport-fit=cover`, so the page can use the area under the system bars;
 *  * `referrer: no-referrer`, the same rule the Go proxy enforces at the network
 *    layer, told to the WebView as well;
 *  * 16px form fields below 700px, which is the one *visual* difference between
 *    the two apps' rendering: it is the size at which mobile engines stop zooming
 *    a focused field, and it makes the composer legible on a phone.
 *
 * Deliberately **not** ported, because they exist for the shell's own chrome rather
 * than for the page: the IME-state mirror (this screen lets the WebView shrink for
 * the keyboard instead — see the note on `WindowInsets.safeDrawing`), and the
 * drag-drop / clipboard-paste helpers (this app attaches files through its own
 * picker).
 *
 * 0.3.6 also injected a page-side reserve here, so the sidebar footer would stop above
 * this screen's own floating settings button. 0.3.8 removed that button, and the
 * reserve with it: the page's own settings row is the only settings entry at the
 * bottom now, and it keeps its own place. The measurement, the DSH sidebar's DOM and
 * the `[data-slot=…]` anchors it relied on are written down in
 * `_ops/docs/dsh-mobile.md`, should a wrapper ever need to move that footer again.
 */
private fun tunePage(view: WebView) {
    view.evaluateJavascript(
        "(function(){" +
            "var m=document.querySelector('meta[name=viewport]');" +
            "if(!m){m=document.createElement('meta');m.name='viewport';" +
            "m.content='width=device-width,initial-scale=1,maximum-scale=5,viewport-fit=cover';" +
            "document.head.appendChild(m);}" +
            "var rp=document.querySelector('meta[name=referrer]');" +
            "if(!rp){rp=document.createElement('meta');rp.name='referrer';" +
            "rp.content='no-referrer';document.head.appendChild(rp);}" +
            "if(window.__byokPageTuned)return;window.__byokPageTuned=1;" +
            "var st=document.createElement('style');st.id='byok-page-tuned';" +
            "st.textContent='@media (max-width:700px){'" +
            "+'input:not([type=checkbox]):not([type=radio]),select,textarea{font-size:16px}'" +
            "+'button,a[role=button],label,[role=button]{touch-action:manipulation}}';" +
            "document.head.appendChild(st);" +
            "})();",
        null,
    )
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
    onOpenSettings: () -> Unit,
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
                // The page could not be reached, so "where does this page come
                // from" is the question the user now has — and that answer lives
                // on the settings screen.
                Button(onClick = onOpenSettings) { Text(stringResource(R.string.web_open_settings)) }
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
 * The URIs a file-chooser result carries.
 *
 * Three shapes arrive here and all three are normal: a `clipData` list when the
 * page allowed several files, a single `data` URI when it did not, and *nothing
 * at all* when the photo came from the camera we handed an `EXTRA_OUTPUT` URI to.
 * That last one is why the staged capture target is passed in — without it, a
 * photo would read as "the user chose nothing" and the upload would silently do
 * nothing.
 */
private fun urisFrom(data: Intent?, captureUri: Uri?): List<Uri>? {
    val clip = data?.clipData
    if (clip != null && clip.itemCount > 0) {
        return (0 until clip.itemCount).map { clip.getItemAt(it).uri }
    }
    data?.data?.let { return listOf(it) }
    return captureUri?.let { listOf(it) }
}

/** A staged photo destination: the file to clean up, and the URI the camera writes. */
private data class CaptureTarget(val file: java.io.File, val uri: Uri)

/**
 * A destination for a photo, inside the app's own cache.
 *
 * The camera writes through the FileProvider for the same reason the update
 * download does: the app holds no storage permission, and the page receives an
 * ordinary `content://` URI it can read without knowing where the bytes live.
 * The file is disposable — the cache may reclaim it, and it is deleted as soon as
 * the request it was staged for is answered without it.
 */
private fun newCaptureTarget(context: Context): CaptureTarget? = try {
    val dir = java.io.File(context.cacheDir, "capture").apply { mkdirs() }
    val file = java.io.File.createTempFile("photo-", ".jpg", dir)
    CaptureTarget(file, FileProvider.getUriForFile(context, UpdateInstaller.authority(context), file))
} catch (e: java.io.IOException) {
    SafeLog.w(TAG, "could not stage a photo target; the camera entry is omitted", e)
    null
}

private const val TAG = "WebScreen"
