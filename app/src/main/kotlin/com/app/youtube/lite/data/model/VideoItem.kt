package com.app.youtube.lite.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * One entry in any list the app renders (feed, search results, related, library).
 *
 * `@Immutable` is load-bearing: it lets the Compose compiler skip *every* composable that
 * receives a `VideoItem` when the reference is unchanged, so scrolling a long feed does not
 * re-run card content. Every field is a value type — no collections, no lambdas — which keeps
 * `equals` allocation-free.
 */
@Immutable
@Serializable
data class VideoItem(
    val id: String,
    val title: String,
    val channel: String = "",
    val channelId: String? = null,
    val thumbnailUrl: String? = null,
    val durationSeconds: Long = 0L,
    val viewCount: Long = 0L,
    val publishedText: String? = null,
    val isLive: Boolean = false,
) {
    /** Canonical watch URL, used as the extractor input and as a share target. */
    val watchUrl: String get() = "https://www.youtube.com/watch?v=$id"
}
