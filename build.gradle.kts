plugins {
    alias(libs.plugins.android.application) apply false
    // `org.jetbrains.kotlin.android` is deliberately absent: AGP 9 compiles
    // Kotlin itself (built-in Kotlin) and that plugin is incompatible with the
    // new DSL. The Compose and serialization compiler plugins are still applied
    // per module, because built-in Kotlin does not replace them.
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
