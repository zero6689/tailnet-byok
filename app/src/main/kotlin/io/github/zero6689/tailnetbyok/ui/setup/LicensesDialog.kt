package io.github.zero6689.tailnetbyok.ui.setup

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.zero6689.tailnetbyok.R
import io.github.zero6689.tailnetbyok.domain.LicenseEntry
import io.github.zero6689.tailnetbyok.domain.LicenseIndexParser

/**
 * The open-source licence screen: what this APK distributes, and under what terms.
 *
 * # Why it is in the app at all
 *
 * `app/build.gradle.kts` strips `META-INF/LICENSE`, `META-INF/NOTICE` and
 * `META-INF/DEPENDENCIES` from the APK, because the gomobile AAR ships Go licence
 * files that collide with AGP's packaging. That keeps the build working and takes
 * the notice out of the binary — which is exactly where BSD-3-Clause and Apache-2.0
 * require it. The repository's notices were complete; the APK carried none of them.
 *
 * # Why the bodies are read on demand
 *
 * The 21 texts total under 100 KB, but only one is ever on screen. Reading the
 * selected body when it is opened keeps the dialog a list of names rather than a
 * hundred kilobytes of prose the UI has already paid to parse.
 */
@Composable
internal fun LicensesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val index = remember {
        readAsset(context, LicenseIndexParser.ASSET_PATH)
            ?.let { text -> runCatching { LicenseIndexParser.parse(text) }.getOrNull() }
    }
    var open by remember { mutableStateOf<LicenseEntry?>(null) }
    val entry = open

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry?.modules ?: stringResource(R.string.licences_title)) },
        text = {
            when {
                index == null -> Text(stringResource(R.string.licences_unavailable))
                entry == null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    index.notes.forEach { note ->
                        Text(
                            note,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    LazyColumn(Modifier.height(320.dp)) {
                        items(index.entries, key = { it.key }) { item ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { open = item }
                                    .padding(vertical = 6.dp),
                            ) {
                                Text(item.modules, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    item.licence,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                else -> Text(
                    readAsset(context, entry.asset) ?: stringResource(R.string.licences_unavailable),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .height(320.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.licences_close)) }
        },
        dismissButton = {
            if (entry != null) {
                TextButton(onClick = { open = null }) { Text(stringResource(R.string.licences_back)) }
            }
        },
    )
}

/** Reads a UTF-8 asset, or null when it is not in this build. */
private fun readAsset(context: Context, path: String): String? =
    runCatching { context.assets.open(path).bufferedReader().use { it.readText() } }.getOrNull()
