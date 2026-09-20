package com.app.youtube.lite.player

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.app.youtube.lite.R
import com.app.youtube.lite.core.util.L
import com.app.youtube.lite.data.model.PlaybackBundle
import com.app.youtube.lite.data.model.readPlaybackBundle
import com.app.youtube.lite.data.model.toArguments
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The UI's single window onto playback.
 *
 * Playback itself lives in [PlaybackService] (a `MediaSessionService`), because that is the only
 * way audio survives the app being backgrounded or swiped away — a service is a foreground
 * process with a media notification, an `Activity` is not. This class therefore owns a
 * [MediaController]: a thin, marshalled proxy to the session.
 *
 * Everything the UI needs is folded into one [PlayerUiState] flow:
 *
 *  • callbacks arrive on the main thread and simply re-read the controller (`publish()`), so the
 *    state can never drift from the truth — there is no second copy of playback state to keep in
 *    sync;
 *  • while playing, a 4 Hz ticker refreshes position/buffer progress: fast enough to look
 *    perfectly smooth, slow enough that a 60 fps UI is not woken 60 times a second for a
 *    progress bar;
 *  • resume positions are written at most every 15 s and on every pause/stop, then remembered by
 *    [PlaybackPositions].
 */
class PlayerManager(
    context: Context,
    private val positions: PlaybackPositions,
    private val scope: CoroutineScope,
) {

    private val appContext = context.applicationContext

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var ticker: Job? = null
    private var pending: Pending? = null

    /** Candidate id the UI last saw, used to *detect* a service-side recovery hop. */
    private var lastCandidateId: String? = null
    private var lastPersistedMs: Long = 0L

    private data class Pending(
        val bundle: PlaybackBundle,
        val startPositionMs: Long,
        val autoPlay: Boolean,
    )

    val player: Player? get() = controller

    // ---------------------------------------------------------------- lifecycle

    /**
     * Binds to [PlaybackService]. Idempotent; must be called from the main thread (the
     * `MediaController` is created on the calling looper).
     */
    fun connect() {
        if (controllerFuture != null) return
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        controllerFuture = future
        future.addListener(
            Runnable {
                val created = runCatching { future.get() }
                    .onFailure { L.e("PlayerManager") { "media session connection failed: $it" } }
                    .getOrNull()
                if (created == null) {
                    controllerFuture = null
                    _state.update {
                        it.copy(
                            connected = false,
                            errorMessage = appContext.getString(R.string.player_service_unavailable),
                        )
                    }
                    return@Runnable
                }
                controller = created
                created.addListener(listener)
                _state.update { it.copy(connected = true, errorMessage = null) }
                pending?.let { request ->
                    pending = null
                    dispatch(PlaybackCommands.PLAY_RUNG, request.bundle.toArguments(request.startPositionMs))
                    if (!request.autoPlay) created.pause()
                }
                publish()
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    /**
     * Unbinds from the session and forgets the local state. Safe to call more than once.
     *
     * Note what this does *not* do: it never stops playback. The `MediaSessionService` owns the
     * player, and unbinding a controller must not be able to interrupt audio — an Activity being
     * destroyed (task swiped away, screen off, configuration killed by the system) is not the same
     * event as the user pressing stop. Stopping is [stop], and it is only ever called by an
     * explicit action.
     */
    fun release() {
        ticker?.cancel()
        ticker = null
        persistPosition(force = true)
        controller?.removeListener(listener)
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        lastCandidateId = null
        _state.value = PlayerUiState()
    }

    // ---------------------------------------------------------------- commands

    /**
     * Hands the whole candidate ladder to the service, which selects [PlaybackBundle.selectedIndex]
     * (or the first rung it can open) and walks down on failure.
     */
    fun play(bundle: PlaybackBundle, startPositionMs: Long = 0L, autoPlay: Boolean = true) {
        _state.update { it.copy(errorMessage = null, recovering = false) }
        lastCandidateId = null
        lastPersistedMs = 0L
        if (controller == null) {
            pending = Pending(bundle, startPositionMs, autoPlay)
            connect()
            return
        }
        dispatch(PlaybackCommands.PLAY_RUNG, bundle.toArguments(startPositionMs))
        if (!autoPlay) controller?.pause()
    }

    /** Manual quality change: switches to another rung of the ladder that is already loaded. */
    fun selectRung(index: Int) {
        dispatch(
            PlaybackCommands.SELECT_RUNG,
            android.os.Bundle(1).apply { putInt(PlaybackCommands.ARG_RUNG_INDEX, index) },
        )
    }

    fun setAudioOnly(audioOnly: Boolean) {
        dispatch(
            PlaybackCommands.SET_AUDIO_ONLY,
            android.os.Bundle(1).apply { putBoolean(PlaybackCommands.ARG_AUDIO_ONLY, audioOnly) },
        )
    }

    /** Re-prepares the current item from its current position after a fatal error. */
    fun retry() {
        _state.update { it.copy(errorMessage = null, recovering = true) }
        dispatch(PlaybackCommands.RETRY, android.os.Bundle.EMPTY)
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) pause() else play()
    }

    fun play() {
        val c = controller ?: return
        if (c.playbackState == Player.STATE_IDLE) {
            retry()
            return
        }
        c.play()
    }

    fun pause() {
        controller?.pause()
        persistPosition(force = true)
    }

    fun stop() {
        persistPosition(force = true)
        controller?.let {
            it.stop()
            it.clearMediaItems()
        }
        lastCandidateId = null
        _state.value = PlayerUiState(connected = controller != null)
    }

    fun seekTo(positionMs: Long) {
        val c = controller ?: return
        c.seekTo(positionMs.coerceAtLeast(0L))
        publish()
    }

    /** Relative seek used by the double-tap gestures. */
    fun seekBy(deltaMs: Long) {
        val c = controller ?: return
        val target = (c.currentPosition + deltaMs).coerceIn(0L, c.duration.coerceAtLeast(0L))
        c.seekTo(target)
        publish()
    }

    fun setSpeed(speed: Float) {
        val c = controller ?: return
        val clamped = speed.coerceIn(0.25f, 4f)
        c.setPlaybackSpeed(clamped)
        _state.update { it.copy(speed = clamped) }
    }

    // ---------------------------------------------------------------- internals

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) startTicker() else stopTicker()
            if (!isPlaying) persistPosition(force = true)
            publish()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            publish()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            publish()
        }

        override fun onPlayerError(error: PlaybackException) {
            // The service may still be recovering onto another rung; only report what it gives up on.
            L.w("PlayerManager") { "player error ${error.errorCodeName}: ${error.message}" }
            _state.update {
                it.copy(
                    errorMessage = error.message
                        ?: appContext.getString(R.string.video_playback_failed),
                    isBuffering = false,
                    recovering = false,
                )
            }
        }
    }

    private fun dispatch(command: androidx.media3.session.SessionCommand, args: android.os.Bundle) {
        val c = controller ?: return
        val future = runCatching { c.sendCustomCommand(command, args) }.getOrNull() ?: return
        future.addListener(
            Runnable {
                runCatching { future.get() }.onFailure { error ->
                    L.w("PlayerManager") { "command ${command.customAction} failed: $error" }
                }
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    /** Re-reads the controller into [state]; cheap and idempotent (called from callbacks + ticker). */
    private fun publish() {
        val c = controller ?: return
        val item = c.currentMediaItem
        val bundle = item?.readPlaybackBundle()
        val candidateId = bundle?.current?.candidateId
        val durationMs = c.duration

        if (candidateId != null) {
            val hopped = lastCandidateId != null && candidateId != lastCandidateId
            if (hopped) {
                L.i("PlayerManager") { "recovered onto rung $candidateId" }
            }
            lastCandidateId = candidateId
            // The UI never asked for this rung, so the service switched it after a failure: show
            // the recovery in the player instead of an unexplained stall.
            if (hopped) _state.update { it.copy(recovering = true) }
        }

        _state.update { previous ->
            previous.copy(
                connected = true,
                mediaId = item?.mediaId ?: previous.mediaId,
                title = item?.mediaMetadata?.title?.toString() ?: bundle?.title ?: previous.title,
                channel = item?.mediaMetadata?.artist?.toString() ?: bundle?.channel ?: previous.channel,
                thumbnailUrl = item?.mediaMetadata?.artworkUri?.toString()
                    ?: bundle?.thumbnailUrl
                    ?: previous.thumbnailUrl,
                isPlaying = c.isPlaying,
                isBuffering = c.playbackState == Player.STATE_BUFFERING,
                isEnded = c.playbackState == Player.STATE_ENDED,
                playWhenReady = c.playWhenReady,
                positionMs = c.currentPosition.coerceAtLeast(0L),
                durationMs = if (durationMs > 0L) durationMs else (bundle?.durationMs ?: 0L),
                bufferedPositionMs = c.bufferedPosition.coerceAtLeast(0L),
                speed = c.playbackParameters.speed,
                audioOnly = c.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO),
                qualityLabel = bundle?.current?.label ?: previous.qualityLabel,
                qualityOptions = bundle?.candidates?.map { it.label } ?: previous.qualityOptions,
                selectedQualityIndex = bundle?.selectedIndex ?: previous.selectedQualityIndex,
                // Cleared as soon as the new rung actually renders a frame.
                recovering = previous.recovering &&
                    !(c.playbackState == Player.STATE_READY && c.isPlaying),
            )
        }
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch(Dispatchers.Main.immediate) {
            while (isActive) {
                publish()
                delay(POSITION_TICK_MS)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    /**
     * Writes the resume position, throttled: at most one write per [PERSIST_INTERVAL_MS] unless
     * [force] is set (pause/stop/release), so a 40-minute video costs a handful of tiny writes
     * instead of thousands.
     */
    private fun persistPosition(force: Boolean) {
        val c = controller ?: return
        val mediaId = c.currentMediaItem?.mediaId ?: return
        val position = c.currentPosition
        if (position <= 0L) return
        val due = position > lastPersistedMs + PERSIST_INTERVAL_MS || position < lastPersistedMs - PERSIST_INTERVAL_MS
        if (!force && !due) return
        lastPersistedMs = position
        positions.put(mediaId, position)
    }

    private companion object {
        /** 4 Hz: smooth to the eye, ~15× cheaper than per-frame progress updates. */
        const val POSITION_TICK_MS = 250L
        const val PERSIST_INTERVAL_MS = 15_000L
    }
}
