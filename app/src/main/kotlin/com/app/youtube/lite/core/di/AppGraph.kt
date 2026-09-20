package com.app.youtube.lite.core.di

import android.app.Application
import com.app.youtube.lite.auth.AuthManager
import com.app.youtube.lite.auth.SecureStore
import com.app.youtube.lite.core.net.Http
import com.app.youtube.lite.core.net.NetworkMonitor
import com.app.youtube.lite.core.util.AppJson
import com.app.youtube.lite.core.util.L
import com.app.youtube.lite.data.innertube.InnerTubeClient
import com.app.youtube.lite.data.prefs.SettingsRepository
import com.app.youtube.lite.data.repo.ExtractorBootstrap
import com.app.youtube.lite.data.repo.FeedRepository
import com.app.youtube.lite.data.repo.StreamResolver
import com.app.youtube.lite.player.PlaybackPositions
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * The app's object graph — deliberately hand-written instead of a DI framework.
 *
 * Dagger/Hilt would add a processor, generated code and (for KSP/Hilt) tens of milliseconds to
 * every build plus a few hundred KB of dex to the APK. This app has one `Application`, one
 * `Activity` and one `Service`; a single lazily-initialized graph is smaller, faster and, more
 * importantly, *predictable*: nothing is constructed until it is first touched, so app start-up
 * costs exactly one thing — reading the settings DataStore.
 *
 * Lifetime notes:
 *  • every singleton here is process-wide, and the whole process is one user session;
 *  • [PlaybackService] runs in the same process (the manifest does not fork it), so it may use
 *    this graph directly instead of marshalling data through Binder;
 *  • the graph holds no `Activity` or `View` — only `Application` context, so nothing can leak.
 */
class AppGraph private constructor(private val app: Application) {

    /**
     * Long-lived background scope. `SupervisorJob` keeps one failed job (a dead extraction, a
     * network hiccup) from tearing down the settings collector; the handler logs instead of
     * crashing the app — a helper failure must never take the UI down with it.
     */
    val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
            L.e("AppGraph") { "background coroutine failed: $throwable" }
        },
    )

    /** The single HTTP/2 client: cookies, connection pool and timeouts are shared by everything. */
    val http: OkHttpClient get() = Http.client

    val settings: SettingsRepository by lazy { SettingsRepository(app, scope) }

    val auth: AuthManager by lazy {
        AuthManager(store = SecureStore(app), cookieJar = Http.cookieJar, json = AppJson)
    }

    val network: NetworkMonitor by lazy { NetworkMonitor(app) }

    val client: InnerTubeClient by lazy { InnerTubeClient(http, auth, AppJson) }

    val feeds: FeedRepository by lazy { FeedRepository(client, auth) }

    /**
     * NewPipeExtractor is initialised once, before the first extraction, and never before: the
     * library parses YouTube's player JavaScript with Rhino, and paying that cost at app start
     * would delay the first frame of the home feed for a capability most launches never need.
     */
    val resolver: StreamResolver by lazy {
        ExtractorBootstrap.install(http)
        StreamResolver(settingsProvider = { settings.current })
    }

    val positions: PlaybackPositions by lazy { PlaybackPositions(app) }

    /** Restores the saved session asynchronously; the UI shows the anonymous feed until it lands. */
    fun warmUp() {
        scope.launch { auth.restore() }
    }

    companion object {
        @Volatile
        private var instance: AppGraph? = null

        fun install(app: Application): AppGraph =
            instance ?: synchronized(this) { instance ?: AppGraph(app).also { instance = it } }

        /** Fails loudly in debug, degrades gracefully in release. */
        fun get(): AppGraph = requireNotNull(instance) { "AppGraph.install() was never called" }
    }
}
