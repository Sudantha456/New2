package com.app.youtube.lite.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * One playable rendition of a video.
 *
 * A spec is either
 *  • **split** (`videoUrl` + `audioUrl` non-null) — the DASH-style pair the player joins with
 *    a `MergingMediaSource`, or
 *  • **muxed** (`videoUrl` only, `audioUrl == null`) — a progressive rendition used as the
 *    last-resort recovery rung.
 *
 * Kept intentionally free of anything but strings/ints: the whole list is serialised into the
 * `MediaItem`'s extras so the `MediaSessionService` can build the right `MediaSource` without
 * ever re-running extraction.
 */
@Immutable
@Serializable
data class MediaSpec(
    val candidateId: String,
    val label: String,
    val videoUrl: String? = null,
    val videoMime: String? = null,
    val videoHeight: Int = 0,
    val videoFps: Int = 0,
    val videoCodec: String? = null,
    val videoRange: String? = null,
    val audioUrl: String? = null,
    val audioMime: String? = null,
    val audioCodec: String? = null,
    val audioBitrate: Int = 0,
    val audioRange: String? = null,
    /** Adaptive (HLS) rendition — required for live streams, where no progressive URL exists. */
    val hls: Boolean = false,
    /** Media CDN edges are picky about which client identity negotiated the URL. */
    val androidUserAgent: Boolean = true,
    /** Higher = preferred earlier when picking a default rung. */
    val score: Int = 0,
) {
    val isSplit: Boolean get() = !videoUrl.isNullOrEmpty() && !audioUrl.isNullOrEmpty()
    val isMuxed: Boolean get() = !videoUrl.isNullOrEmpty() && audioUrl.isNullOrEmpty()
    val isPlayable: Boolean get() = !videoUrl.isNullOrEmpty() || !audioUrl.isNullOrEmpty()
}

/**
 * Everything the playback service needs to play a video and to recover from a bad rendition:
 * metadata for the notification/UI, the full candidate ladder, and the current rung.
 *
 * This object is serialised (JSON, ~2-6 KB) into `MediaItem.requestMetadata.extras`, which is
 * the only channel that survives the `MediaController` → `MediaSessionService` Binder hop.
 */
@Immutable
@Serializable
data class PlaybackBundle(
    val videoId: String,
    val title: String,
    val channel: String,
    val channelId: String? = null,
    val thumbnailUrl: String? = null,
    val durationMs: Long = 0L,
    val viewCount: Long = 0L,
    val candidates: List<MediaSpec> = emptyList(),
    val selectedIndex: Int = 0,
    val related: List<VideoItem> = emptyList(),
    /** Set when the user asked for audio-only playback (background listening). */
    val audioOnly: Boolean = false,
) {
    val current: MediaSpec? get() = candidates.getOrNull(selectedIndex)

    fun withNextCandidate(): PlaybackBundle? {
        val next = selectedIndex + 1
        if (next >= candidates.size) return null
        return copy(selectedIndex = next)
    }

    fun withCandidate(index: Int): PlaybackBundle {
        if (index !in candidates.indices) return this
        return copy(selectedIndex = index)
    }
}
