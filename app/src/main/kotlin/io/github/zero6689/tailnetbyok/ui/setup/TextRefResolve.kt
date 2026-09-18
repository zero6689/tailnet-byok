package io.github.zero6689.tailnetbyok.ui.setup

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.zero6689.tailnetbyok.core.text.TextRef
import io.github.zero6689.tailnetbyok.domain.ConnectionTester

/**
 * The one place a [TextRef] becomes text.
 *
 * Everything below the UI carries resource ids, never sentences (see
 * `core.text.TextRef` for why). These two helpers are the boundary: they read
 * the active configuration and produce the string, so translation happens
 * exactly once and only at render time.
 */

@Composable
fun TextRef.resolve(): String =
    if (args.isEmpty()) {
        stringResource(res)
    } else {
        stringResource(res, *args.toTypedArray())
    }

/**
 * Resolves a test verdict.
 *
 * The warning count is a quantity, so it goes through `pluralStringResource` —
 * which is what makes "with 1 warning" and "with 2 warnings" both correct, and
 * lets a language with more than two plural forms do the right thing.
 */
@Composable
fun ConnectionTester.Summary.resolve(): String = when {
    detail != null -> detail.resolve()
    isQuantity -> pluralStringResource(res, args.first() as Int, *args.toTypedArray())
    args.isEmpty() -> stringResource(res)
    else -> stringResource(res, *args.toTypedArray())
}
