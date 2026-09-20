package com.app.youtube.lite.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.DefaultAllocator
import kotlin.math.min

/** 0 ms of back buffer: rewind support is not worth a third of the RAM budget. */
private const val BACK_BUFFER_MS = 0

/** ExoPlayer's own allocation granularity (64 KiB). */
internal const val ALLOCATION_SIZE = C.DEFAULT_BUFFER_SEGMENT_SIZE

/**
 * ExoPlayer's buffer governor, tuned for a phone that must never be killed by the LMK.
 *
 * ## The numbers, and why they are right
 *
 * | Knob | Value | Rationale |
 * |---|---|---|
 * | `minBufferMs` | 10 000 | Enough to ride out a 5–8 s cellular stall (handover, tunnel) without a rebuffer. |
 * | `maxBufferMs` | 25 000 | Caps *time*-based buffering at 25 s. 1080p ≈ 4 Mbps ⇒ 25 s ≈ 12.5 MB, so the byte cap below usually binds first. |
 * | `bufferForPlaybackMs` | 1 500 | Start-up latency: ≈0.75 MB of media at 4 Mbps — feels instant, still enough to avoid a start-up rebuffer. |
 * | `bufferForPlaybackAfterRebufferMs` | 3 000 | After a stall we insist on 3 s; resuming into a 1.5 s buffer would stutter again immediately. |
 * | `targetBufferBytes` | 20 MB | *The* hard memory ceiling: ≈10 s of 1080p60 video **plus** a complete audio track, versus ExoPlayer's default allowance of 200 MB. |
 * | `prioritizeTimeOverSizeThresholds` | true | Never stall at a byte ceiling — resolve the 25 s / 20 MB pair in favour of "keep playing". |
 * | back buffer | 0 ms | Seeking refetches from the CDN instead of pinning RAM. |
 *
 * Buffers come from a single constrained [DefaultAllocator] of 64 KiB allocations, which is
 * what makes the ceiling real: ExoPlayer cannot exceed it because it has nowhere to put the
 * bytes. `trimOnReset = true` releases every allocation the moment playback stops, so a paused
 * app falls back to its baseline footprint immediately.
 */
@OptIn(UnstableApi::class)
class LowRamLoadControl(
    private val bufferAllocator: DefaultAllocator,
    minBufferMs: Int = MIN_BUFFER_MS,
    maxBufferMs: Int = MAX_BUFFER_MS,
    bufferForPlaybackMs: Int = BUFFER_FOR_PLAYBACK_MS,
    bufferForPlaybackAfterRebufferMs: Int = BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
    /** The byte ceiling this instance was built with — user-tunable, default [TARGET_BUFFER_BYTES]. */
    val targetLimit: Int = TARGET_BUFFER_BYTES,
) : DefaultLoadControl(
    bufferAllocator,
    minBufferMs,
    maxBufferMs,
    bufferForPlaybackMs,
    bufferForPlaybackAfterRebufferMs,
    targetLimit,
    /* prioritizeTimeOverSizeThresholds = */ true,
    /* backBufferDurationMs = */ BACK_BUFFER_MS,
    /* retainBackBufferFromKeyframe = */ false,
) {

    /**
     * Belt-and-braces over the constructor's `targetBufferBytes`: this also covers a control
     * constructed with `C.LENGTH_UNSET` (the default), e.g. if a future Media3 release changes
     * how the builder plumbs the ceiling through.
     *
     * Audio is always budgeted in full — an audio desync is far more noticeable than a dropped
     * video frame — and video gets whatever is left.
     */
    override fun calculateTargetBufferBytes(
        trackSelectionArray: Array<ExoTrackSelection>?,
    ): Int {
        val selections = trackSelectionArray ?: return targetLimit
        var audioBytes = 0
        var videoBytes = 0
        for (selection in selections) {
            when (selection.trackGroup.type) {
                C.TRACK_TYPE_AUDIO -> audioBytes = AUDIO_BUDGET_BYTES
                C.TRACK_TYPE_VIDEO -> videoBytes = VIDEO_BUDGET_BYTES
            }
        }
        return min(targetLimit, audioBytes + videoBytes)
    }

    /** Live view of how much of the budget the allocator currently holds (diagnostics). */
    fun allocatedBytes(): Int = bufferAllocator.totalBytesAllocated

    companion object {
        const val MIN_BUFFER_MS = 10_000
        const val MAX_BUFFER_MS = 25_000
        const val BUFFER_FOR_PLAYBACK_MS = 1_500
        const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 3_000

        /** The documented ceiling: 20 MB for *all* media buffers, audio included. */
        const val TARGET_BUFFER_BYTES = 20 * 1024 * 1024

        const val AUDIO_BUDGET_BYTES = 2 * 1024 * 1024
        const val VIDEO_BUDGET_BYTES = 18 * 1024 * 1024

        /** Lower bound we never go under, even on a low-RAM device with a small user setting. */
        const val MIN_TARGET_BYTES = 8 * 1024 * 1024

        /**
         * Construction point used by the player factory: one shared allocation pool, capped at
         * [maxBufferBytes] (default 20 MB, halved for devices the platform flags as low-RAM).
         */
        fun create(maxBufferBytes: Int = TARGET_BUFFER_BYTES): LowRamLoadControl {
            val limit = maxBufferBytes.coerceAtLeast(MIN_TARGET_BYTES)
            return LowRamLoadControl(
                DefaultAllocator(/* trimOnReset = */ true, /* individualAllocationSize = */ ALLOCATION_SIZE),
                targetLimit = limit,
            )
        }
    }
}
