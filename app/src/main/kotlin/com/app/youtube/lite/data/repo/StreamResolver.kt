package com.app.youtube.lite.data.repo

import com.app.youtube.lite.core.util.L
import com.app.youtube.lite.data.model.AppSettings
import com.app.youtube.lite.data.model.MediaSpec
import com.app.youtube.lite.data.model.PlaybackBundle
import com.app.youtube.lite.data.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.IOException

/** Thrown when a video cannot be turned into a playable rendition ladder. */
class StreamResolutionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Resolves a watch URL into an ordered ladder of playable renditions using NewPipeExtractor.
 *
 * The ladder is the heart of "flawless playback":
 *
 * ```
 * rung 0..n-1  split DASH-style  — video-only + audio-only joined by MergingMediaSource
 *                                  (best quality; H.264 preferred for power, AV1 opt-in)
 * rung n       muxed progressive — single container, lowest common denominator
 * rung n+1     HLS               — only for live content, where nothing else exists
 * ```
 *
 * Playback always *starts* at rung 0 and walks down on failure (see
 * `PlaybackService`), so a single bad rendition — a 403 from one CDN edge, a codec the device
 * cannot decode, a throttled segment — costs one skipped rung, not the video.
 *
 * Extraction itself is CPU-heavy (Rhino runs YouTube's player JavaScript to decipher the
 * signature and the throttling parameter), so calls are funnelled through a small semaphore:
 * two concurrent extractions already saturate a mid-range SoC, and any more would only steal
 * cycles from the decoder that is rendering the *current* video.
 */
class StreamResolver(
    private val settingsProvider: () -> AppSettings,
    private val youtubeServiceId: Int = 0,
) {

    private val gate = Semaphore(permits = 2)

    /**
     * @param watchUrl canonical `https://www.youtube.com/watch?v=…` URL
     */
    suspend fun resolve(watchUrl: String): PlaybackBundle = withContext(Dispatchers.IO) {
        gate.withPermit {
            val settings = settingsProvider()
            val info = extractWithRetry(watchUrl)
            val candidates = buildLadder(info, settings)
            if (candidates.isEmpty()) {
                throw StreamResolutionException(
                    "No playable rendition for \"${info.name}\" (age-restricted or unsupported)",
                )
            }
            PlaybackBundle(
                videoId = info.id.orEmpty(),
                title = info.name.orEmpty(),
                channel = info.uploaderName.orEmpty(),
                channelId = info.uploaderUrl,
                thumbnailUrl = pickThumbnail(info.thumbnails),
                durationMs = info.duration.coerceAtLeast(0L) * 1000L,
                viewCount = info.viewCount,
                candidates = candidates,
                selectedIndex = 0,
                related = relatedOf(info),
            ).also {
                L.i("Resolver") {
                    "resolved ${it.videoId}: ${candidates.size} rungs, best=${candidates.first().label}"
                }
            }
        }
    }

    /** Related videos only — used by the watch screen without re-resolving streams. */
    suspend fun related(watchUrl: String): List<VideoItem> = withContext(Dispatchers.IO) {
        gate.withPermit { relatedOf(extractWithRetry(watchUrl)) }
    }

    // ── Extraction ───────────────────────────────────────────────────────────

    private suspend fun extractWithRetry(watchUrl: String): StreamInfo {
        var lastError: Throwable? = null
        for (attempt in 1..EXTRACT_ATTEMPTS) {
            try {
                val service = NewPipe.getService(youtubeServiceId)
                return StreamInfo.getInfo(service, watchUrl)
            } catch (io: IOException) {
                lastError = io
                L.w("Resolver") { "extraction attempt $attempt failed: ${io.message}" }
                if (attempt < EXTRACT_ATTEMPTS) {
                    // Transient CDN/edge failure — a short pause usually lands on a healthy one.
                    delay(EXTRACT_RETRY_DELAY_MS * attempt)
                }
            } catch (extraction: ExtractionException) {
                throw StreamResolutionException(
                    extraction.message ?: "YouTube rejected the extraction request",
                    extraction,
                )
            }
        }
        throw StreamResolutionException("Extraction failed after $EXTRACT_ATTEMPTS attempts", lastError)
    }

    // ── Ladder construction ──────────────────────────────────────────────────

    private fun buildLadder(info: StreamInfo, settings: AppSettings): List<MediaSpec> {
        val cap = effectiveHeightCap(settings)
        val ladder = ArrayList<MediaSpec>(MAX_RUNGS)

        // Live streams only exist as adaptive manifests.
        if (info.streamType == StreamType.LIVE_STREAM) {
            info.hlsUrl?.takeIf { it.isNotBlank() }?.let { url ->
                ladder += MediaSpec(
                    candidateId = "hls-live",
                    label = "Live · adaptive (HLS)",
                    videoUrl = url,
                    hls = true,
                    androidUserAgent = false,
                    score = 10_000,
                )
            }
        }

        // Primary: split video-only + audio-only joined by MergingMediaSource.
        val audio = pickAudio(info.audioStreams, settings)
        if (audio != null) {
            val videoRenditions = info.videoOnlyStreams
                .asSequence()
                .filter { it.isUsableFor(settings) }
                .filter { it.height in 1..cap }
                .sortedByDescending { it.score(settings) }
                .take(MAX_SPLIT_RUNGS)
                .toList()

            for (video in videoRenditions) {
                ladder += splitSpec(video, audio, settings)
            }
        }

        // Fallback: a muxed progressive rendition needs no merging and no second decoder.
        info.videoStreams
            .asSequence()
            .filter { it.isUsableFor(settings) }
            .filter { it.height in 1..cap }
            .maxByOrNull { it.score(settings) }
            ?.let { muxed ->
                ladder += MediaSpec(
                    candidateId = "muxed-${muxed.itag}",
                    label = "${muxed.resolution} · ${codecName(muxed.codec)} · muxed",
                    videoUrl = muxed.url,
                    videoMime = videoMimeOf(muxed.codec),
                    videoHeight = muxed.height,
                    videoFps = muxed.fps,
                    videoCodec = codecName(muxed.codec),
                    androidUserAgent = true,
                    score = muxed.score(settings) - MUXED_PENALTY,
                )
            }

        return ladder.distinctBy { it.candidateId }
    }

    private fun splitSpec(video: VideoStream, audio: AudioStream, settings: AppSettings): MediaSpec {
        val bitrateKbps = (audio.averageBitrate.coerceAtLeast(0)) / 1000
        val label = buildString(48) {
            append(video.resolution.ifEmpty { "${video.height}p" })
            if (video.fps > 30) append(video.fps).append("fps ")
            append(" · ").append(codecName(video.codec))
            append(" · ").append(audioCodecName(audio.codec)).append(' ').append(bitrateKbps).append("k")
        }
        return MediaSpec(
            candidateId = "v${video.itag}+a${audio.itag}",
            label = label,
            videoUrl = video.url,
            videoMime = videoMimeOf(video.codec),
            videoHeight = video.height,
            videoFps = video.fps,
            videoCodec = codecName(video.codec),
            audioUrl = audio.url,
            audioMime = audioMimeOf(audio.codec),
            audioCodec = audioCodecName(audio.codec),
            audioBitrate = audio.averageBitrate,
            androidUserAgent = true,
            score = video.score(settings) + audio.score(settings) / 100,
        )
    }

    /**
     * Audio selection is a *battery* decision first and a quality decision second.
     *
     * AAC-LC (`m4a`) is decoded by every SoC's dedicated audio DSP at a fraction of the power
     * an Opus decode costs in software on older devices, so AAC is preferred and Opus is the
     * fallback. Dubbed and descriptive tracks are excluded unless nothing else exists.
     */
    private fun pickAudio(streams: List<AudioStream>, settings: AppSettings): AudioStream? {
        if (streams.isEmpty()) return null
        val usable = streams.filter { stream ->
            // Platform type: the extractor may leave the track type unset.
            val type = stream.audioTrackType
            type == null || type == AudioTrackType.ORIGINAL
        }
        val pool = usable.ifEmpty { streams }
        return pool.maxByOrNull { stream ->
            val codecBonus = if (isAac(stream.codec)) AAC_BONUS else 0
            val bitrate = stream.averageBitrate.coerceAtLeast(0)
            val dataSaverPenalty = if (settings.dataSaver) bitrate / 8 else 0
            codecBonus + bitrate - dataSaverPenalty
        }
    }

    private fun relatedOf(info: StreamInfo): List<VideoItem> =
        info.relatedItems
            .asSequence()
            .filterIsInstance<StreamInfoItem>()
            .filter { !it.isShortFormContent }
            .take(RELATED_LIMIT)
            .map { item ->
                VideoItem(
                    id = item.url.substringAfter("v=", "").substringBefore('&'),
                    title = item.name.orEmpty(),
                    channel = item.uploaderName.orEmpty(),
                    channelId = item.uploaderUrl,
                    thumbnailUrl = pickThumbnail(item.thumbnails),
                    durationSeconds = item.duration.coerceAtLeast(0L),
                    viewCount = item.viewCount.coerceAtLeast(0L),
                    publishedText = item.textualUploadDate,
                    isLive = item.streamType == StreamType.LIVE_STREAM,
                )
            }
            .filter { it.id.length == 11 }
            .toList()

    // ── Policy helpers ───────────────────────────────────────────────────────

    private fun effectiveHeightCap(settings: AppSettings): Int {
        val configured = settings.qualityCap.maxHeight
        return if (settings.dataSaver) minOf(configured, DATA_SAVER_MAX_HEIGHT) else configured
    }

    /** Codec gate: honour the user's compatibility/power preferences. */
    private fun VideoStream.isUsableFor(settings: AppSettings): Boolean {
        if (url.isNullOrBlank()) return false
        return when {
            isAv1(codec) -> settings.allowAv1
            isVp9(codec) -> settings.allowVp9
            else -> true
        }
    }

    private fun VideoStream.score(settings: AppSettings): Int {
        var score = height
        if (fps > 30) score += fps / 2
        score += when {
            isAvc(codec) -> if (settings.preferH264) H264_BONUS else 0
            isVp9(codec) -> VP9_BONUS
            isAv1(codec) -> AV1_BONUS
            else -> 0
        }
        // Prefer smaller bitrate at equal height — direct data/battery saving.
        score -= (bitrate.coerceAtLeast(0) / 100_000)
        return score
    }

    private fun AudioStream.score(settings: AppSettings): Int =
        averageBitrate.coerceAtLeast(0) + if (isAac(codec)) AAC_BONUS else 0

    private fun pickThumbnail(images: List<Image>): String? {
        if (images.isEmpty()) return null
        val best = images
            .filter { it.width in THUMBNAIL_MIN_WIDTH..THUMBNAIL_MAX_WIDTH }
            .minByOrNull { it.width }
            ?: images.filter { it.width > 0 }.maxByOrNull { it.width }
        return best?.url
    }

    private fun codecName(codec: String?): String = when {
        codec == null -> "unknown"
        isAvc(codec) -> "H.264"
        isVp9(codec) -> "VP9"
        isAv1(codec) -> "AV1"
        isAac(codec) -> "AAC"
        isOpus(codec) -> "Opus"
        else -> codec.substringBefore('.').ifEmpty { "unknown" }
    }

    private fun audioCodecName(codec: String?): String = codecName(codec)

    private fun isAvc(codec: String?): Boolean =
        codec != null && (codec.startsWith("avc", true) || codec.startsWith("h264", true))

    private fun isVp9(codec: String?): Boolean =
        codec != null && (codec.startsWith("vp9", true) || codec.startsWith("vp09", true))

    private fun isAv1(codec: String?): Boolean =
        codec != null && codec.startsWith("av01", true)

    private fun isAac(codec: String?): Boolean =
        codec != null && (codec.startsWith("mp4a", true) || codec.contains("aac", true))

    private fun isOpus(codec: String?): Boolean =
        codec != null && codec.contains("opus", true)

    private fun videoMimeOf(codec: String?): String =
        if (isVp9(codec) || isAv1(codec)) "video/webm" else "video/mp4"

    private fun audioMimeOf(codec: String?): String =
        if (isOpus(codec)) "audio/webm" else "audio/mp4"

    private companion object {
        const val EXTRACT_ATTEMPTS = 2
        const val EXTRACT_RETRY_DELAY_MS = 400L
        const val MAX_SPLIT_RUNGS = 3
        const val MAX_RUNGS = 6
        const val RELATED_LIMIT = 24
        const val MUXED_PENALTY = 5_000
        const val H264_BONUS = 40
        const val VP9_BONUS = 25
        const val AV1_BONUS = 5
        const val AAC_BONUS = 200_000
        const val DATA_SAVER_MAX_HEIGHT = 480
        const val THUMBNAIL_MIN_WIDTH = 320
        const val THUMBNAIL_MAX_WIDTH = 640
    }
}
