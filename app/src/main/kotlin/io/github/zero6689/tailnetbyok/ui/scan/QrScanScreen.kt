package io.github.zero6689.tailnetbyok.ui.scan

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.zero6689.tailnetbyok.R
import io.github.zero6689.tailnetbyok.domain.LuminanceFrame
import io.github.zero6689.tailnetbyok.domain.QrDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The scanner: a viewfinder, and one way out.
 *
 * # Why the app has one at all
 *
 * This app deliberately had no camera for a long time — the setup code could be
 * opened by any camera application, because that is what a custom URI scheme is
 * for. What that arrangement could not do is read a code that arrived as an
 * *image*: a screenshot in a chat, a photo of a printed slip, a code on a screen
 * the user cannot tap. The camera is requested here, on this screen, and used for
 * exactly one thing: turning a QR code into text. No frame is written anywhere,
 * no frame leaves the decoder, and what the screen returns is a string — which
 * then goes through the same parse-show-confirm path as a link that arrived over
 * the operating system. See `docs/SECURITY-MODEL.md`.
 *
 * # Why the picture path is not a fallback
 *
 * Denying the camera is a legitimate answer, so it is not a dead end: a code can
 * also be read from a picture the user picks, which needs no permission at all
 * (the system picker grants one file). That is the same door as the "upload" path
 * in the DSH screen, and it is the reason this screen is usable with the camera
 * permanently denied.
 */
@Composable
fun QrScanScreen(
    onResult: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    // Whether the user has refused once. Android stops showing the dialog after
    // the second refusal, and a button that silently does nothing is worse than
    // sending the user to the settings page.
    var refused by remember { mutableStateOf(false) }
    var cameraFailed by remember { mutableStateOf(false) }
    var imageProblem by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf(false) }
    var torchAvailable by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    // Held so that leaving the screen can *unbind*, not merely drop the view. A
    // use case stays bound to the activity's lifecycle after its `PreviewView`
    // leaves the composition, which is a camera that keeps running — and an
    // indicator light that keeps glowing — with no screen showing it.
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    // One result per visit: the analyzer keeps running until the screen is left,
    // and a second code read a moment later would replace the confirmation the
    // user is already looking at. An atomic, not a Compose state: the analyzer
    // runs on its own thread, and this is read from there.
    val delivered = remember { AtomicBoolean(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { allowed ->
        granted = allowed
        refused = !allowed
    }

    val imageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        reading = true
        imageProblem = false
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                val image = ImageFiles.read(context, uri)
                if (image == null) {
                    null
                } else {
                    QrDecoder.decodeArgb(image.pixels, image.width, image.height)
                }
            }
            reading = false
            when {
                text != null && delivered.compareAndSet(false, true) -> onResult(text)
                else -> imageProblem = true
            }
        }
    }

    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose {
            camera?.cameraControl?.enableTorch(false)
            provider?.unbindAll()
            executor.shutdown()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted && !cameraFailed) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    }
                },
                update = { view ->
                    // Bound once per view: `bindToLifecycle` throws if the same
                    // use cases are bound twice, and Compose calls `update` on
                    // every recomposition.
                    if (view.tag == null) {
                        view.tag = true
                        scope.launch {
                            runCatching {
                                bind(
                                    context = context,
                                    lifecycleOwner = lifecycleOwner,
                                    previewView = view,
                                    executor = executor,
                                    onFrame = { frame, rotation ->
                                        if (!delivered.get()) {
                                            QrDecoder.decode(frame, rotation)?.let { text ->
                                                if (delivered.compareAndSet(false, true)) {
                                                    // Back to the main thread: what
                                                    // follows is a state change, not
                                                    // decoding.
                                                    ContextCompat.getMainExecutor(context)
                                                        .execute { onResult(text) }
                                                }
                                            }
                                        }
                                    },
                                )
                            }.onSuccess { bound ->
                                provider = bound.first
                                camera = bound.second
                                torchAvailable = bound.second.cameraInfo.hasFlashUnit()
                            }.onFailure {
                                cameraFailed = true
                            }
                        }
                    }
                },
            )
        }

        // The camera is not the only way in, so a refusal shows the alternatives
        // rather than an error.
        if (!granted || cameraFailed) {
            PermissionPanel(
                cameraFailed = cameraFailed,
                refused = refused,
                onGrant = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onOpenSettings = { openAppSettings(context) },
                onPickImage = { pickImage(imageLauncher) },
                reading = reading,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.scan_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.scan_close), color = Color.White)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            imageProblem.takeIf { it }?.let {
                Text(
                    stringResource(R.string.scan_no_code_in_image),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                )
            }

            if (granted && !cameraFailed) {
                Text(
                    stringResource(R.string.scan_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (torchAvailable) {
                        OutlinedButton(
                            onClick = {
                                val next = !torchOn
                                torchOn = next
                                camera?.cameraControl?.enableTorch(next)
                            },
                        ) {
                            Text(
                                stringResource(
                                    if (torchOn) R.string.scan_torch_off else R.string.scan_torch_on,
                                ),
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = { pickImage(imageLauncher) },
                        enabled = !reading,
                    ) {
                        Text(stringResource(R.string.scan_pick_image))
                    }
                }
            }
        }
    }
}

/** "Pick a picture" — one call site, so the request cannot drift between buttons. */
private fun pickImage(
    launcher: androidx.activity.result.ActivityResultLauncher<PickVisualMediaRequest>,
) {
    launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
}

private fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/**
 * Binds the camera to the screen's lifecycle and pushes frames into [onFrame].
 *
 * CameraX handles the parts that are easy to get wrong by hand — which camera to
 * open, the display rotation, tearing the session down when the screen goes away
 * — and returns both the [ProcessCameraProvider] and the [Camera]: the camera
 * drives the torch, and the provider is what the caller unbinds on the way out.
 */
private suspend fun bind(
    context: Context,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    previewView: PreviewView,
    executor: java.util.concurrent.Executor,
    onFrame: (LuminanceFrame, Int) -> Unit,
): Pair<ProcessCameraProvider, Camera> {
    val provider = awaitProvider(context)

    val preview = Preview.Builder().build().also {
        it.setSurfaceProvider(previewView.surfaceProvider)
    }

    val analysis = ImageAnalysis.Builder()
        // The screen only ever wants the *newest* frame: a queue of stale ones
        // would slow the preview down without finding a code any sooner.
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()

    analysis.setAnalyzer(executor) { image: ImageProxy ->
        try {
            CameraFrames.luminance(image)?.let { frame ->
                onFrame(frame, image.imageInfo.rotationDegrees)
            }
        } finally {
            // Closing is mandatory: CameraX stops delivering frames if any
            // analysis image is left open.
            image.close()
        }
    }

    val selector = CameraSelector.DEFAULT_BACK_CAMERA
    // Unbound first: this screen can be entered twice, and binding the same use
    // cases to a second lifecycle is an exception, not a re-bind.
    provider.unbindAll()
    val bound = provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
    return provider to bound
}

private suspend fun awaitProvider(context: Context): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                try {
                    continuation.resume(future.get())
                } catch (error: Exception) {
                    continuation.resumeWithException(error)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

@Composable
private fun PermissionPanel(
    cameraFailed: Boolean,
    refused: Boolean,
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit,
    onPickImage: () -> Unit,
    reading: Boolean,
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(
                        if (cameraFailed) R.string.scan_camera_failed else R.string.scan_camera_title,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.scan_camera_why),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (!cameraFailed) {
                    Button(onClick = onGrant, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.scan_grant_camera))
                    }
                    if (refused) {
                        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.scan_open_settings))
                        }
                    }
                }

                OutlinedButton(
                    onClick = onPickImage,
                    enabled = !reading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (reading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(stringResource(R.string.scan_pick_image))
                }
            }
        }
    }
}
