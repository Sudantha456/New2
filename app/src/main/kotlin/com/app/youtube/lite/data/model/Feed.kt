package com.app.youtube.lite.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * Identifies a paged InnerTube `browse` feed.
 *
 * The `browseId`s are the same tokens YouTube's own web client sends, which is why the app
 * needs no bespoke per-feed logic: one pager drives Home, Subscriptions, Trending, Liked
 * videos and Watch history alike. `params` is the optional protobuf-ish cursor YouTube uses to
 * pick a tab *within* a feed (e.g. "Latest" vs "Popular" on a channel).
 */
@Immutable
@Serializable
data class FeedKey(
    val browseId: String,
    val params: String? = null,
    val title: String = "",
    val requiresAuth: Boolean = false,
) {
    companion object {
        val Home = FeedKey(browseId = "FEwhat_to_watch", title = "Home")
        val Subscriptions = FeedKey(
            browseId = "FEsubscriptions",
            title = "Subscriptions",
            requiresAuth = true,
        )
        val Trending = FeedKey(browseId = "FEtrending", title = "Trending")
        val Library = FeedKey(
            browseId = "FElibrary",
            title = "Library",
            requiresAuth = true,
        )
        val History = FeedKey(
            browseId = "FEhistory",
            title = "History",
            requiresAuth = true,
        )

        /** Liked videos: the `LL` playlist, addressed as `VLLL` in the browse namespace. */
        val Liked = FeedKey(
            browseId = "VLLL",
            title = "Liked videos",
            requiresAuth = true,
        )
    }
}

/**
 * Render state of a paged list. Modelled as a sealed hierarchy so that every screen renders
 * exactly one branch and the shimmer state can never be confused with "loaded but empty".
 */
@Immutable
sealed interface FeedState {
    /** First load in flight — the UI shows shimmer cards. */
    data object Loading : FeedState

    @Immutable
    data class Content(
        val items: List<VideoItem>,
        val loadingMore: Boolean = false,
        val refreshing: Boolean = false,
        val endReached: Boolean = false,
    ) : FeedState {
        val isEmpty: Boolean get() = items.isEmpty()
    }

    @Immutable
    data class Error(val message: String, val requiresAuth: Boolean = false) : FeedState
}
