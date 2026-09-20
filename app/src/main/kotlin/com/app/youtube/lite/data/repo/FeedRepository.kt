package com.app.youtube.lite.data.repo

import com.app.youtube.lite.auth.AuthManager
import com.app.youtube.lite.core.util.L
import com.app.youtube.lite.data.innertube.InnerTubeClient
import com.app.youtube.lite.data.innertube.InnerTubeException
import com.app.youtube.lite.data.innertube.parseContinuation
import com.app.youtube.lite.data.innertube.parseItems
import com.app.youtube.lite.data.model.FeedKey
import com.app.youtube.lite.data.model.FeedState
import com.app.youtube.lite.data.model.VideoItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Paged feed engine.
 *
 * One implementation drives Home, Subscriptions, Trending, Liked videos, Watch history and
 * Search results, because InnerTube exposes all of them as `browse`/`search` pages with the
 * same renderer shapes.
 *
 * Performance-relevant decisions:
 *  • **Sessions are cached in memory for the process lifetime.** Switching bottom-nav tabs is
 *    then a `StateFlow` read (no network, no parsing, no shimmer) — the single biggest
 *    perceived-speed win in the app.
 *  • **One in-flight request per feed**, guarded by a [Mutex], so a double tap on a tab or a
 *    rotation cannot fire two identical extractions.
 *  • **Append, never rebuild.** `loadMore` copies the list once and appends; Compose only
 *    composes the newly added items because the existing ones keep their identity.
 *  • The state flowed to the UI is `@Immutable` ([FeedState]) and only changes when the
 *    content actually changes, which keeps `collectAsStateWithLifecycle` from re-composing a
 *    list that did not move.
 */
class FeedRepository(
    private val client: InnerTubeClient,
    private val auth: AuthManager,
) {

    private class Session(val key: FeedKey) {
        val state = MutableStateFlow<FeedState>(FeedState.Loading)
        val mutex = Mutex()
        var continuation: String? = null
        var loadedOnce = false
    }

    private val sessions = ConcurrentHashMap<String, Session>()

    fun observe(key: FeedKey): StateFlow<FeedState> = session(key).state.asStateFlow()

    /** Loads the first page unless the feed already has content. */
    suspend fun ensureLoaded(key: FeedKey) {
        val session = session(key)
        if (session.loadedOnce) return
        refresh(key)
    }

    suspend fun refresh(key: FeedKey) {
        val session = session(key)
        session.mutex.withLock {
            session.state.value = currentWith(session, refreshing = true, loading = !session.loadedOnce)
            loadPage(session, continuation = null, append = false)
        }
    }

    suspend fun loadMore(key: FeedKey) {
        val session = session(key)
        val continuation = session.continuation ?: return
        session.mutex.withLock {
            val current = session.state.value
            if (current !is FeedState.Content || current.loadingMore) return
            session.state.value = current.copy(loadingMore = true)
            loadPage(session, continuation = continuation, append = true)
        }
    }

    /** Drops every cached feed — used on login/logout, where auth-scoped content goes stale. */
    fun invalidateAll() {
        for (session in sessions.values) {
            session.continuation = null
            session.loadedOnce = false
            session.state.value = FeedState.Loading
        }
        sessions.clear()
        L.i("Feed") { "feed sessions invalidated" }
    }

    fun invalidate(key: FeedKey) {
        sessions.remove(key.cacheKey)
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private fun session(key: FeedKey): Session = sessions.getOrPut(key.cacheKey) { Session(key) }

    private suspend fun loadPage(session: Session, continuation: String?, append: Boolean) {
        val key = session.key
        if (key.requiresAuth && !auth.isLoggedIn) {
            session.state.value = FeedState.Error(
                message = "Sign in to see ${key.title.lowercase()}",
                requiresAuth = true,
            )
            return
        }

        try {
            val page = client.browse(
                browseId = key.browseId,
                params = key.params,
                continuation = continuation,
            )
            val fresh = parseItems(page)
            session.continuation = parseContinuation(page)
            session.loadedOnce = true

            val items: List<VideoItem>
            if (append) {
                val existing = (session.state.value as? FeedState.Content)?.items ?: emptyList()
                val seen = HashSet<String>(existing.size + fresh.size)
                for (item in existing) seen.add(item.id)
                val ready = ArrayList<VideoItem>(existing.size + fresh.size)
                ready.addAll(existing)
                for (item in fresh) if (seen.add(item.id)) ready.add(item)
                items = ready
            } else {
                items = fresh
            }

            session.state.value = FeedState.Content(
                items = items,
                loadingMore = false,
                refreshing = false,
                endReached = session.continuation == null,
            )
            L.d("Feed") { "${key.browseId}: ${fresh.size} items (total ${items.size})" }
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            L.w("Feed") { "load failed for ${key.browseId}: $error" }
            val previous = session.state.value
            val message = friendlyMessage(error)
            session.state.value = if (append && previous is FeedState.Content) {
                // Keep what the user already has; a failed "load more" is not an error screen.
                previous.copy(loadingMore = false, endReached = true)
            } else {
                FeedState.Error(message = message, requiresAuth = isAuthError(error))
            }
        }
    }

    private fun currentWith(session: Session, refreshing: Boolean, loading: Boolean): FeedState {
        val current = session.state.value
        return when {
            current is FeedState.Content -> current.copy(refreshing = refreshing)
            loading -> FeedState.Loading
            else -> current
        }
    }

    private fun friendlyMessage(error: Throwable): String = when {
        error is InnerTubeException && error.message?.contains("HTTP 404") == true ->
            "This feed is not available for your account."
        isAuthError(error) -> "Your session expired. Sign in again to continue."
        error is InnerTubeException -> "YouTube request failed: ${error.message?.take(90)}"
        else -> "Could not load the feed. Check your connection and retry."
    }

    private fun isAuthError(error: Throwable): Boolean {
        val message = error.message.orEmpty()
        return message.contains("HTTP 401") || message.contains("HTTP 403")
    }

    private val FeedKey.cacheKey: String get() = "$browseId|${params.orEmpty()}"
}
