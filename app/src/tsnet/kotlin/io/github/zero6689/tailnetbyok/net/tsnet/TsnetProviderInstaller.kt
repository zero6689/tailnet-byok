package io.github.zero6689.tailnetbyok.net.tsnet

import android.os.Build
import io.github.zero6689.tailnetbyok.mobile.Mobile
import io.github.zero6689.tailnetbyok.core.log.SafeLog
import io.github.zero6689.tailnetbyok.net.ProviderRegistry

/**
 * Registers the embedded tailnet provider with [ProviderRegistry].
 *
 * # Why this class is loaded by name, not by import
 *
 * This file compiles against `Mobile`, which only exists after
 * `gomobile bind` has generated it. The default build — the one a contributor
 * gets from a fresh clone, and the one CI runs without a Go toolchain — has no
 * such class. A direct reference from shared code would therefore break the
 * build for everyone who is not shipping the native bridge.
 *
 * So [ProviderRegistry] reaches this object with `Class.forName` and a single
 * `install` method. The indirection is not architectural taste; it is the price
 * of making the expensive half of this project optional.
 *
 * Two consequences worth knowing:
 *
 *  * **R8 must keep this class.** A release build cannot see the reference, so
 *    `app/proguard-rules.pro` keeps `**.net.tsnet.**` explicitly. Without that
 *    rule the failure is release-only and looks like "the feature silently
 *    disappeared".
 *  * **The class must stay trivially constructible.** It is resolved during app
 *    startup; doing real work in this package's initialiser would move native
 *    library loading into the cold-start path.
 */
object TsnetProviderInstaller {

    private const val TAG = "TsnetInstall"

    /**
     * Called by [ProviderRegistry] exactly once.
     *
     * Registers lazily — the factory itself does nothing until a provider is
     * actually requested and started, so a user who never enables the embedded
     * node never pays for loading `libgojni.so`.
     */
    @JvmStatic
    fun install(registry: ProviderRegistry) {
        if (!isAbiSupported()) {
            // The overwhelming cause on x86_64 emulators: an arm64-only AAR
            // installs happily and then throws UnsatisfiedLinkError on first
            // use. Failing here turns that into a clear message.
            SafeLog.w(TAG, "no gomobile native library for ${Build.SUPPORTED_ABIS.joinToString()}")
            return
        }
        registry.registerEmbedded { TsnetConnectivityProvider() }
        SafeLog.i(TAG, "embedded tailnet provider registered (bridge ${runCatching { Mobile.version() }.getOrNull()})")
    }

    /**
     * Whether this device's ABI is one the bridge was built for.
     *
     * `gomobile bind` defaults to `arm64-v8a,armeabi-v7a,x86,x86_64`, so the
     * honest answer is usually "yes". The check exists because a trimmed AAR
     * (arm64-only, which is how most people ship to keep the APK small) is a
     * normal configuration and its failure mode is otherwise an
     * `UnsatisfiedLinkError` deep inside a native call.
     */
    private fun isAbiSupported(): Boolean =
        Build.SUPPORTED_ABIS.any { abi ->
            abi in setOf("arm64-v8a", "armeabi-v7a", "armeabi", "x86", "x86_64")
        }
}
