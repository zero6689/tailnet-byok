package io.github.zero6689.tailnetbyok

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import io.github.zero6689.tailnetbyok.ui.setup.SetupRoute
import io.github.zero6689.tailnetbyok.ui.theme.TailnetByokTheme

/**
 * The app's only activity.
 *
 * There is exactly one, and that is a deliberate limit rather than an unfinished
 * one: a single-activity Compose app has no inter-activity navigation to get
 * wrong, no exported components to secure, and no task-affinity surprises. See
 * the note in `AndroidManifest.xml` — this app owns no other IPC surface at all.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TailnetByokTheme {
                Root()
            }
        }
    }
}

/**
 * Root composable.
 *
 * Reads the object graph from the `Application` rather than receiving it through
 * an intent extra or a static holder, which is what makes a configuration change
 * structurally unable to produce a second graph — and therefore a second
 * embedded node fighting over one state directory.
 */
@Composable
private fun Root() {
    val context = LocalContext.current
    val container = (context.applicationContext as TailnetByokApp).container
    SetupRoute(container = container)
}
