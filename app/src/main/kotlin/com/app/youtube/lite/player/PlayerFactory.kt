package com.app.youtube.lite.player

import android.app.ActivityManager
import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.app.youtube.lite.data.model.AppSettings
import com.app.youtube.lite.data.model.QualityCap

/**
 * Builds the one `ExoPlayer` instance the app owns, wired for low memory, low power and fast
 * start-up. Every choice here is a deliberate trade-off:
 *
 *  • **Asynchronous codec queueing** — `forceEnableMediaCodecAsynchronousQueueing()` makes
 *    `MediaCodec` hand frames over through its callbacks instead of a renderer thread blocking
 *    on `dequeueOutputBuffer` ~60 times per second. On mid-range SoCs this is the difference
 *    between "perfectly buffered but stuttering" and smooth playback; it also frees the renderer
 *    thread enough that UI work stays off the critical path.
 *  • **Decoder fallback on** — if the platform's first-choice codec fails to configure (common
 *    on some vendor ROMs) ExoPlayer retries the next candidate instead of erroring out.
 *  • **Extension renderers off** — software FFmpeg/AV1 decoders cost far more power than the
 *    quality they add. Hardware-or-fail, with the resolver's codec gate (AV1/VP9 opt-in)
 *    keeping unsupported renditions out of the ladder in the first place.
 *  • **`WAKE_MODE_NETWORK`** + `handleAudioBecomingNoisy` — audio keeps playing with the screen
 *    off without a wake lock we have to manage, and playback pauses when headphones are pulled.
 *  • **`setEnableAudioTrackPlaybackParams(true)`** — required for speed control (0.25×–4×) to
 *    work through the platform's own resampler instead of ours.
 *  • **Track selection born from settings** — the quality ceiling and the data-saver bitrate cap
 *    are enforced by the player as well as by the resolver, so nothing (not even a future
 *    adaptive source) can exceed what the user asked for.
 */
@OptIn(UnstableApi::class)
object PlayerFactory {

    /**
     * @param settings read lazily at build time, so the caller never has to hold a snapshot.
     */
    fun create(
        context: Context,
        settings: () -> AppSettings,
        mediaSourceFactory: MediaSource.Factory,
    ): ExoPlayer {
        val current = settings()

        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            .setEnableDecoderFallback(true)
            .forceEnableMediaCodecAsynchronousQueueing()
            .setEnableAudioTrackPlaybackParams(true)

        val trackSelector = DefaultTrackSelector(context).apply {
            parameters = trackParameters(context, current)
        }

        return ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(
                LowRamLoadControl.create(maxBufferBytes = bufferBudgetBytes(context, current)),
            )
            .setAudioAttributes(audioAttributes(), /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setUseLazyPreparation(true)
            // Silence skipping re-reads the whole audio stream hunting for gaps: CPU and battery
            // spent on a feature no video viewer asked for.
            .setSkipSilenceEnabled(false)
            .build()
    }

    /**
     * Applies a settings change to a live player without recreating it — the quality ceiling and
     * the data-saver switch can both change mid-playback from the settings sheet.
     */
    fun applySettings(player: ExoPlayer, settings: AppSettings) {
        val updated: TrackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setMaxVideoSize(Int.MAX_VALUE, settings.qualityCap.maxHeight)
            .setMaxVideoBitrate(if (settings.dataSaver) DATA_SAVER_BITRATE else Int.MAX_VALUE)
            .build()
        player.trackSelectionParameters = updated
    }

    /** Disabling the video track releases its decoder and surface — the core of background audio. */
    fun setAudioOnly(player: ExoPlayer, audioOnly: Boolean) {
        val disabled = if (audioOnly) setOf(C.TRACK_TYPE_VIDEO) else emptySet()
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setDisabledTrackTypes(disabled)
            .build()
    }

    @OptIn(UnstableApi::class)
    private fun trackParameters(
        context: Context,
        settings: AppSettings,
    ): DefaultTrackSelector.Parameters =
        DefaultTrackSelector.Parameters.Builder(context)
            .setMaxVideoSize(Int.MAX_VALUE, settings.qualityCap.maxHeight)
            .setMaxVideoBitrate(if (settings.dataSaver) DATA_SAVER_BITRATE else Int.MAX_VALUE)
            .build()

    /**
     * Buffer budget: the user's setting, halved on devices the platform reports as low-RAM.
     * A 2 GB device cannot afford a 20 MB media buffer on top of decoders and UI.
     */
    private fun bufferBudgetBytes(context: Context, settings: AppSettings): Int {
        val configured = settings.maxBufferMegabytes.coerceIn(MIN_BUFFER_MB, MAX_BUFFER_MB)
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val bytes = configured * 1024 * 1024
        return if (activityManager?.isLowRamDevice == true) bytes / 2 else bytes
    }

    private fun audioAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .build()

    private const val DATA_SAVER_BITRATE = 1_200_000
    private const val MIN_BUFFER_MB = 8
    private const val MAX_BUFFER_MB = 32
}
