package com.app.youtube.lite.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import com.app.youtube.lite.core.net.UserAgents
import com.app.youtube.lite.core.util.L
import com.app.youtube.lite.data.model.PlaybackBundle
import com.app.youtube.lite.data.model.readPlaybackBundle
import okhttp3.CacheControl
import okhttp3.OkHttpClient

/**
 * Builds the `MediaSource` for one [MediaItem] out of the [PlaybackBundle] it carries.
 *
 * Three shapes are produced, in the order the resolver ranks them:
 *
 * 1. **Split (DASH-style)** — `MergingMediaSource(videoOnly, audioOnly)`. This is what makes
 *    1080p60 possible at all: YouTube only muxes low resolutions into progressive files, so
 *    high-quality playback *is* a two-stream merge. Both halves stream over the shared
 *    HTTP/2 connection pool, and each half gets its own decoder — the video decoder is never
 *    asked to also parse an audio track.
 * 2. **Muxed/progressive** — a single `ProgressiveMediaSource`, used as the recovery rung when
 *    the split pair cannot be played on this device.
 * 3. **HLS** — live streams only, via `HlsMediaSource`.
 *
 * Data reaches all of them through `OkHttpDataSource`, i.e. through the app's single pooled
 * HTTP/2 client: connection reuse, the shared cookie jar (so age-restricted/members-only media
 * is authorised) and the shared timeout policy apply to every media range request.
 *
 * A `DashMediaSource` builder is included for completeness: YouTube's own MPD is offered by the
 * extractor, and while the split path is preferred (the MPD expires quickly and is often
 * rejected for non-web clients), having the factory here means a future adaptive path needs no
 * new plumbing.
 */
@OptIn(UnstableApi::class)
class LiteMediaSourceFactory(
    private val http: OkHttpClient,
) : MediaSource.Factory {

    override fun setDrmSessionManagerProvider(
        drmSessionManagerProvider: DrmSessionManagerProvider,
    ): MediaSource.Factory = this

    override fun setLoadErrorHandlingPolicy(
        loadErrorHandlingPolicy: LoadErrorHandlingPolicy,
    ): MediaSource.Factory = this

    override fun getSupportedTypes(): IntArray =
        intArrayOf(C.CONTENT_TYPE_OTHER, C.CONTENT_TYPE_DASH, C.CONTENT_TYPE_HLS)

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val bundle = mediaItem.readPlaybackBundle()
        val spec = bundle?.current
        if (bundle == null || spec == null) {
            // Nothing we can play: hand ExoPlayer a progressive source on the item's own URI so
            // the failure surfaces as a normal PlaybackException and the service's recovery
            // ladder can take over.
            L.w("MediaSource") { "media item without a playback bundle: ${mediaItem.mediaId}" }
            return progressive(dataSourceFactory(androidUserAgent = true)).createMediaSource(mediaItem)
        }

        val dataSourceFactory = dataSourceFactory(spec.androidUserAgent)

        return when {
            spec.hls -> {
                val hlsItem = mediaItem.buildUpon()
                    .setUri(spec.videoUrl)
                    .setMimeType(HLS_MIME)
                    .build()
                HlsMediaSource.Factory(dataSourceFactory).createMediaSource(hlsItem)
            }

            spec.isSplit -> {
                val videoItem = mediaItem.buildUpon()
                    .setUri(spec.videoUrl)
                    .setMimeType(spec.videoMime)
                    .build()
                val audioItem = mediaItem.buildUpon()
                    .setUri(spec.audioUrl)
                    .setMimeType(spec.audioMime)
                    .build()
                // `adjustPeriodTimeOffsets = false`: both halves start at 0 and must stay locked
                // to that origin; letting ExoPlayer shift the periods introduces A/V drift.
                MergingMediaSource(
                    false,
                    progressive(dataSourceFactory).createMediaSource(videoItem),
                    progressive(dataSourceFactory).createMediaSource(audioItem),
                )
            }

            else -> progressive(dataSourceFactory).createMediaSource(
                mediaItem.buildUpon()
                    .setUri(spec.videoUrl ?: spec.audioUrl)
                    .setMimeType(spec.videoMime)
                    .build(),
            )
        }
    }

    /** Only used by the optional MPD path; kept public for that reason. */
    fun dashSource(mediaItem: MediaItem): MediaSource =
        DashMediaSource.Factory(dataSourceFactory(androidUserAgent = false))
            .createMediaSource(mediaItem)

    private fun progressive(dataSourceFactory: OkHttpDataSource.Factory): ProgressiveMediaSource.Factory =
        ProgressiveMediaSource.Factory(dataSourceFactory)
            // Media3's default is 750 ms of "keep loading while the parser is idle"; bumping it
            // lets the extractor pipeline one segment ahead without extra logic.
            .setContinueLoadingCheckIntervalBytes(COPY_BUFFER_BYTES)
            .setLoadErrorHandlingPolicy(
                DefaultLoadErrorHandlingPolicy(FAST_FAIL_RETRY_COUNT),
            )

    /**
     * Media requests carry the identity that negotiated the URL, plus the `Origin`/`Referer`
     * pair `googlevideo` edges expect from a web-context client. Cookies are added by the
     * shared jar, not here, so a login/logout takes effect on the next request with no cache to
     * invalidate.
     */
    private fun dataSourceFactory(androidUserAgent: Boolean): OkHttpDataSource.Factory {
        val userAgent = if (androidUserAgent) UserAgents.ANDROID_APP else UserAgents.DESKTOP
        return OkHttpDataSource.Factory(http)
            .setDefaultRequestProperties(
                mapOf(
                    "User-Agent" to userAgent,
                    "Origin" to MEDIA_ORIGIN,
                    "Referer" to "$MEDIA_ORIGIN/",
                    "Accept-Language" to "en-US,en;q=0.9",
                ),
            )
            // Media segments are immutable: never let a proxy return a stale range. (OkHttp 4
            // dropped the CacheControl.NO_STORE constant, so it is built explicitly.)
            .setCacheControl(NO_STORE_CACHE_CONTROL)
    }

    private companion object {
        val NO_STORE_CACHE_CONTROL: CacheControl = CacheControl.Builder().noStore().build()

        const val HLS_MIME = "application/x-mpegURL"
        const val MEDIA_ORIGIN = "https://www.youtube.com"

        /** 256 KiB read-ahead: one full segment for most renditions, no wasted tail. */
        const val COPY_BUFFER_BYTES = 256 * 1024

        /**
         * Two quick retries inside a single rung. Anything worse (HTTP 403, unsupported codec,
         * expired URL) fails fast so the *ladder* can move to a different rendition instead of
         * stalling on a dead one.
         */
        const val FAST_FAIL_RETRY_COUNT = 2
    }
}
