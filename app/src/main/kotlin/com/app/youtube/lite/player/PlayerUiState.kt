package com.app.youtube.lite.player

import androidx.compose.runtime.Immutable

/**
 * The single, flattened playback state the UI observes.
 *
 * Two reasons this is one object rather than a dozen flows:
 *  • the player screen and the mini player both need *all* of it, and one `@Immutable` value
 *    means exactly one recomposition scope per change instead of one per field;
 *  • it is what the `MediaController` callbacks can be folded into cheaply — the alternative
 *    (a flow per field) allocates on every position tick.
 *
 * Position updates are throttled to ~4 Hz by [PlayerManager]: a 60 Hz progress bar is wasted
 * work (no eye can see a 16 ms move in a bar), while 4 Hz still looks perfectly smooth.
 */
@Immutable
data class PlayerUiState(
    val connected: Boolean = false,
    val mediaId: String? = null,
    val title: String = "",
    val channel: String = "",
    val thumbnailUrl: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isEnded: Boolean = false,
    val playWhenReady: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val speed: Float = 1f,
    val audioOnly: Boolean = false,
    val qualityLabel: String? = null,
    val qualityOptions: List<String> = emptyList(),
    val selectedQualityIndex: Int = 0,
    /** Human-readable failure, surfaced only after the recovery ladder is exhausted. */
    val errorMessage: String? = null,
    /** True while the service is walking the ladder after a rendition failed. */
    val recovering: Boolean = false,
) {
    val hasMedia: Boolean get() = mediaId != null
    val progressFraction: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs) else 0f
}
