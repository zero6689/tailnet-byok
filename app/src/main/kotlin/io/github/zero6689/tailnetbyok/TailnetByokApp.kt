package io.github.zero6689.tailnetbyok

import android.app.Application
import io.github.zero6689.tailnetbyok.core.log.SafeLog
import io.github.zero6689.tailnetbyok.di.AppContainer
import io.github.zero6689.tailnetbyok.net.ProviderRegistry

/**
 * Process entry point.
 *
 * Two things happen here and nowhere else:
 *
 *  1. the object graph is built ([AppContainer]);
 *  2. providers are registered, which is where the optional native bridge is
 *     resolved. That resolution is deliberately *not* eager about loading
 *     `libgojni.so`: the registry only records a factory, so a build that has
 *     the bridge but a user who never enables it pays nothing at cold start.
 *
 * Everything else in this app is lazily constructed, because startup time is the
 * one budget an Android app cannot borrow against.
 */
class TailnetByokApp : Application() {

    /**
     * The graph. `lateinit` rather than nullable because every accessor runs
     * after [onCreate]; a null here would be a programming error, and the crash
     * is a better outcome than a silent fallback to a second graph.
     */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()

        container = AppContainer(applicationContext)

        // Registration never throws: a missing bridge is recorded as a reason
        // string and surfaced in the UI. An app that cannot start because an
        // optional feature is absent would be a poor trade.
        runCatching { ProviderRegistry.initialise() }
            .onFailure { SafeLog.e(TAG, "provider registration failed", it) }

        SafeLog.i(
            TAG,
            "started: available providers=${ProviderRegistry.available().joinToString { it.storageKey }}",
        )
    }

    private companion object {
        const val TAG = "App"
    }
}
