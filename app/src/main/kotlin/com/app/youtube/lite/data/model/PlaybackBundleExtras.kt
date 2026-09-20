package com.app.youtube.lite.data.model

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.app.youtube.lite.core.util.AppJson
import com.app.youtube.lite.core.util.L

/**
 * Bridge between the app's own playback model and Media3's [MediaItem].
 *
 * `MediaController` and `MediaSessionService` live in different processes in the general case
 * (and always talk through Binder), so the only thing that survives the hop is the
 * [MediaItem] itself. The candidate ladder therefore rides inside
 * `requestMetadata.extras` as JSON — a few KB — which is what lets the *service* perform error
 * recovery (falling back to the next rung) without the UI being involved or awake.
 */
private const val EXTRA_PLAYBACK = "com.app.youtube.lite.playback"

/** Where a requested playback should start, in ms. */
private const val START_POSITION_KEY = "start_position_ms"

/** Synthetic URI: local to the app, never dialled by ExoPlayer's data sources. */
private const val SYNTHETIC_SCHEME = "youtubelite"

fun PlaybackBundle.toMediaItem(): MediaItem {
    val uri = Uri.parse("$SYNTHETIC_SCHEME://play/$videoId")
    val extras = Bundle(2).apply {
        putString(EXTRA_PLAYBACK, AppJson.encodeToString(this@toMediaItem))
    }
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(channel)
        .apply { thumbnailUrl?.let { setArtworkUri(Uri.parse(it)) } }
        .setIsBrowsable(false)
        .setIsPlayable(true)
        .build()

    return MediaItem.Builder()
        .setMediaId(videoId)
        .setUri(uri)
        .setMediaMetadata(metadata)
        .setRequestMetadata(
            MediaItem.RequestMetadata.Builder()
                .setMediaUri(uri)
                .setExtras(extras)
                .build(),
        )
        .build()
}

/** Inverse of [toMediaItem]; `null` when the item did not come from this app. */
fun MediaItem.readPlaybackBundle(): PlaybackBundle? {
    val raw = requestMetadata.extras?.getString(EXTRA_PLAYBACK) ?: return null
    return runCatching { AppJson.decodeFromString<PlaybackBundle>(raw) }
        .onFailure { L.w("Playback") { "could not decode playback bundle: $it" } }
        .getOrNull()
}

/**
 * Packs the bundle for a custom `MediaController` -> `MediaSessionService` command. Command
 * arguments are a `Bundle`, so the same JSON string travels here as in [toMediaItem].
 */
fun PlaybackBundle.toArguments(startPositionMs: Long = 0L): Bundle = Bundle(2).apply {
    putString(EXTRA_PLAYBACK, AppJson.encodeToString(this@toArguments))
    putLong(START_POSITION_KEY, startPositionMs)
}

/** Inverse of [toArguments]; `null` when the sender was not this app. */
fun Bundle.readPlaybackBundle(): PlaybackBundle? {
    val raw = getString(EXTRA_PLAYBACK) ?: return null
    return runCatching { AppJson.decodeFromString<PlaybackBundle>(raw) }.getOrNull()
}

/**
 * The start position packed by [toArguments]; `0` when absent.
 */
val Bundle.startPositionMs: Long get() = getLong(START_POSITION_KEY, 0L)

/**
 * Rebuilds the same [MediaItem] with a different rung selected. The metadata is preserved so
 * the notification/artwork never flickers during a recovery hop.
 */
fun MediaItem.withUpdatedBundle(bundle: PlaybackBundle): MediaItem {
    val extras = Bundle(2).apply {
        putString(EXTRA_PLAYBACK, AppJson.encodeToString(bundle))
    }
    return buildUpon()
        .setRequestMetadata(
            MediaItem.RequestMetadata.Builder()
                .setMediaUri(uri)
                .setExtras(extras)
                .build(),
        )
        .build()
}
