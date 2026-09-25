package io.github.zero6689.tailnetbyok.core.text

import androidx.annotation.StringRes

/**
 * A user-visible message, as a resource id plus its arguments.
 *
 * # Why this exists
 *
 * The app is translated (English in `values/`, Chinese in `values-zh/`), so no
 * layer may hand the UI display *text* — a string built with `"$host failed"`
 * somewhere in the domain can never be translated, and the Chinese build would
 * silently show an English sentence in the middle of a translated screen.
 *
 * What crosses the layer boundary is therefore this: a `@StringRes` id and the
 * values to interpolate. `TextRef` is a plain data class and touches no
 * `Context`, so the domain, data and net layers stay free of Android runtime
 * while still producing localizable messages. Only the Compose layer resolves
 * it, via `TextRef.resolve()` (see `ui/setup/TextRefResolve.kt`).
 *
 * The rule for new code: if a `String` would end up on screen, it belongs in
 * `strings.xml` behind an id like this one.
 */
data class TextRef(
    // `@param:` is explicit on purpose. Kotlin 2.2 warns that an annotation on a
    // constructor property lands on the value parameter today and will also land on
    // the field later (KT-73255); the remedies are to say which one you meant, or to
    // pass -Xannotation-default-target=param-property. The flag changes that for
    // *every* annotation in the module, while this says it for this one --
    // `@StringRes` here has always been about the argument a caller passes.
    @param:StringRes val res: Int,
    val args: List<Any> = emptyList(),
) {
    companion object {
        /** A message with no interpolation. */
        fun of(@StringRes res: Int): TextRef = TextRef(res)

        /** A message with positional arguments (`%1$s`, `%2$d`, …). */
        fun of(@StringRes res: Int, vararg args: Any): TextRef = TextRef(res, args.toList())
    }
}
