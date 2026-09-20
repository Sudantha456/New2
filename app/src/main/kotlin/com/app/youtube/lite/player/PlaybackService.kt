package com.app.youtube.lite.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import com.app.youtube.lite.ui.MainActivity
import com.app.youtube.lite.R
import com.app.youtube.lite.core.di.AppGraph
import com.app.youtube.lite.core.util.L
import com.app.youtube.lite.data.model.PlaybackBundle
import com.app.youtube.lite.data.model.readPlaybackBundle
import com.app.youtube.lite.data.model.startPositionMs
import com.app.youtube.lite.data.model.toMediaItem
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns playback for the whole app.
 *
 * Audio must survive the screen turning off, the app being backgrounded and the task being swiped
 * away — which means playback cannot live in an `Activity`. This service is a
 * `MediaSessionService`, so it is a *foreground service* while playing: the system keeps the
 * process alive and shows a media notification with working transport controls, and the lock
 * screen / Bluetooth / Android Auto controls route back into the same [ExoPlayer].
 *
 * ## Seamless recovery
 *
 * The rendition ladder resolved by `StreamResolver` is packed into the `MediaItem` (see
 * `PlaybackBundleExtras`). When a rung fails — a `403` from one CDN edge, a codec this device
 * cannot decode, an expired URL — the service simply re-points the player at the *next* rung at
 * the same position. Only when the whole ladder is exhausted does it re-resolve the video
 * through NewPipeExtractor, and only when that fails too does the user see an error. Most
 * recoveries are therefore invisible, and none of them require the UI to be alive: background
 * audio recovers exactly like foreground video does.
 *
 * ## Autoplay
 *
 * With ~25 s left in the current video the next entry of `related` is resolved *ahead of time* on
 * a background dispatcher, so the transition at the end of a video is a `setMediaItem` — no
 * spinner, no network stall in front of the user.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private val graph: AppGraph get() = AppGraph.get()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null

    @Volatile
    private var prefetched: PlaybackBundle? = null
    private var prefetchJob: Job? = null
    private var refreshJob: Job? = null
    private var monitorJob: Job? = null

    /** Bounded "already played" ring, so autoplay never loops back onto itself. */
    private val playedIds = ArrayDeque<String>(MAX_HISTORY / 2)

    private var recoveryKey: String? = null
    private var recoveryHops = 0
    private var gaveUp = false
    private var audioOnly = false

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()

        player = PlayerFactory.create(
            context = this,
            settings = { graph.settings.current },
            mediaSourceFactory = LiteMediaSourceFactory(graph.http),
        )
        player.addListener(playerListener)

        session = MediaSession.Builder(this, player)
            .setId(SESSION_ID)
            .setCallback(SessionCallback())
            .setSessionActivity(openAppIntent())
            .build()

        ensureNotificationChannel()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(NOTIFICATION_CHANNEL_ID)
                .setChannelName(R.string.notification_channel_name)
                .setNotificationId(NOTIFICATION_ID)
                .build()
                .apply { setSmallIcon(R.drawable.ic_stat_playback) },
        )

        startMonitor()
        L.i(TAG) { "playback service ready" }
    }

    /**
     * Creates the notification channel ourselves, before Media3 ever looks for it.
     *
     * If Media3 creates the channel on first playback it uses its own defaults; doing it here lets
     * us control the two things that matter for a media notification:
     *  • `IMPORTANCE_LOW` — no sound and no heads-up popup. A video starting should not play a
     *    notification tone over the video;
     *  • `VISIBILITY_PUBLIC` with a channel description, so the transport controls appear on the
     *    lock screen and the user can see *why* the channel exists in system settings.
     */
    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
            enableVibration(false)
            enableLights(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
        L.d(TAG) { "created notification channel $NOTIFICATION_CHANNEL_ID" }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /**
     * Swiping the task away is a UI gesture, not a playback decision. When the user has asked for
     * background audio we keep playing (the notification is what makes that legitimate) and drop
     * the video track, which releases its decoder immediately. Otherwise the default behaviour —
     * stop the service — is what the user expects.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (player.playWhenReady && graph.settings.current.backgroundAudio) {
            setAudioOnly(true)
            L.i(TAG) { "task removed; keeping audio alive" }
            return
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        persistPosition()
        monitorJob?.cancel()
        prefetchJob?.cancel()
        refreshJob?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        session?.run {
            player.release()
            release()
        }
        session = null
        L.i(TAG) { "playback service destroyed" }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- commands

    private inner class SessionCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            // The default command set plus ours: without this the controller's custom commands
            // are rejected by the session before they ever reach onCustomCommand.
            val available = SessionCommands.Builder()
                .addAllSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS)
                .addSessionCommands(PlaybackCommands.ALL)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(available)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> = when (customCommand.customAction) {
            PlaybackCommands.ACTION_PLAY_RUNG -> {
                val bundle = args.readPlaybackBundle()
                if (bundle == null) {
                    rejected(SessionResult.RESULT_ERROR_BAD_VALUE)
                } else {
                    playBundle(bundle, args.startPositionMs)
                    accepted()
                }
            }

            PlaybackCommands.ACTION_SELECT_RUNG -> {
                val index = args.getInt(PlaybackCommands.ARG_RUNG_INDEX, -1)
                val bundle = player.currentMediaItem?.readPlaybackBundle()
                if (bundle == null || index !in bundle.candidates.indices) {
                    rejected(SessionResult.RESULT_ERROR_BAD_VALUE)
                } else {
                    resetRecovery()
                    swap(bundle.withCandidate(index), player.currentPosition.coerceAtLeast(0L))
                    accepted()
                }
            }

            PlaybackCommands.ACTION_SET_AUDIO_ONLY -> {
                setAudioOnly(args.getBoolean(PlaybackCommands.ARG_AUDIO_ONLY, false))
                accepted()
            }

            PlaybackCommands.ACTION_RETRY -> {
                resetRecovery()
                player.currentMediaItem?.let { item ->
                    player.setMediaItem(item, player.currentPosition.coerceAtLeast(0L))
                    player.prepare()
                    player.playWhenReady = true
                }
                accepted()
            }

            else -> rejected(SessionResult.RESULT_ERROR_NOT_SUPPORTED)
        }
    }

    private fun accepted(): ListenableFuture<SessionResult> =
        Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))

    private fun rejected(code: Int): ListenableFuture<SessionResult> =
        Futures.immediateFuture(SessionResult(code))

    // ---------------------------------------------------------------- playback

    private fun playBundle(bundle: PlaybackBundle, startPositionMs: Long, autoPlay: Boolean = true) {
        prefetched = null
        prefetchJob?.cancel()
        prefetchJob = null
        resetRecovery()
        player.setMediaItem(bundle.toMediaItem(), startPositionMs.coerceAtLeast(0L))
        player.prepare()
        player.playWhenReady = autoPlay
        if (audioOnly) PlayerFactory.setAudioOnly(player, true)
    }

    /** Re-points the player at a different rung, keeping the position and the play state. */
    private fun swap(bundle: PlaybackBundle, startPositionMs: Long) {
        mainHandler.post {
            if (player.currentMediaItem == null) return@post
            player.setMediaItem(bundle.toMediaItem(), startPositionMs.coerceAtLeast(0L))
            player.prepare()
            player.playWhenReady = true
        }
    }

    private fun setAudioOnly(enabled: Boolean) {
        audioOnly = enabled
        mainHandler.post { PlayerFactory.setAudioOnly(player, enabled) }
    }

    private fun resetRecovery() {
        recoveryKey = null
        recoveryHops = 0
        gaveUp = false
    }

    // ---------------------------------------------------------------- recovery

    private val playerListener = object : Player.Listener {

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> gaveUp = false
                Player.STATE_ENDED -> {
                    persistPosition()
                    autoplayNext()
                }
                else -> Unit
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) persistPosition()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val id = mediaItem?.mediaId ?: return
            if (playedIds.lastOrNull() != id) {
                playedIds.addLast(id)
                while (playedIds.size > MAX_HISTORY) playedIds.removeFirst()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            recover(error)
        }
    }

    /**
     * Walks the ladder. `recoveryHops` is reset whenever a *different* rung starts playing, so a
     * permanently broken CDN edge cannot burn the whole ladder by failing repeatedly on the same
     * rung.
     */
    private fun recover(error: PlaybackException) {
        if (gaveUp) return
        val bundle = player.currentMediaItem?.readPlaybackBundle()
        if (bundle == null) {
            gaveUp = true
            return
        }
        val candidateId = bundle.current?.candidateId.orEmpty()
        if (recoveryKey != candidateId) {
            recoveryKey = candidateId
            recoveryHops = 0
        }
        recoveryHops++
        val position = player.currentPosition.coerceAtLeast(0L)

        val next = bundle.withNextCandidate()
        if (next != null && recoveryHops <= MAX_RUNG_HOPS) {
            L.i(TAG) {
                "rung $candidateId failed (${error.errorCodeName}); next rung ${next.current?.candidateId}"
            }
            swap(next, position)
            return
        }

        if (recoveryHops <= MAX_REFRESHES) {
            L.i(TAG) { "ladder exhausted for ${bundle.videoId}; re-resolving" }
            refresh(bundle, position)
            return
        }

        gaveUp = true
        L.e(TAG) {
            "playback of ${bundle.videoId} failed permanently: ${error.errorCodeName} ${error.message}"
        }
        // Re-preparing the item lets the failure reach the controller once, which is what turns
        // it into a visible message; the service itself never shows UI.
        mainHandler.post {
            player.playWhenReady = false
            val item = player.currentMediaItem ?: return@post
            player.setMediaItem(item, position)
            player.prepare()
        }
    }

    /**
     * Last line of defence: the extractor is asked for a brand-new ladder. Stream URLs are
     * signed and expire, and YouTube can start throttling a host mid-video, so a fresh
     * resolution occasionally succeeds where every cached rung fails.
     */
    private fun refresh(bundle: PlaybackBundle, positionMs: Long) {
        refreshJob?.cancel()
        refreshJob = graph.scope.launch {
            runCatching { graph.resolver.resolve(watchUrl(bundle.videoId)) }
                .onSuccess { fresh ->
                    L.i(TAG) { "re-resolved ${bundle.videoId}: ${fresh.candidates.size} rungs" }
                    mainHandler.post { swap(fresh, positionMs) }
                }
                .onFailure { failure ->
                    // Written from a background dispatcher; hop to the player's thread first.
                    mainHandler.post { gaveUp = true }
                    L.e(TAG) { "re-resolution failed for ${bundle.videoId}: $failure" }
                }
        }
    }

    // ---------------------------------------------------------------- autoplay

    private fun autoplayNext() {
        if (!graph.settings.current.autoplayNext) return
        val ready = prefetched
        prefetched = null
        if (ready != null) {
            L.i(TAG) { "autoplaying prefetched ${ready.videoId}" }
            playBundle(ready, 0L)
            return
        }
        val current = player.currentMediaItem?.readPlaybackBundle() ?: return
        val next = nextRelated(current) ?: return
        refreshJob = graph.scope.launch {
            runCatching { graph.resolver.resolve(next.watchUrl) }
                .onSuccess { fresh -> mainHandler.post { playBundle(fresh, 0L) } }
                .onFailure { L.w(TAG) { "autoplay of ${next.id} failed: $it" } }
        }
    }

    /** Resolves the next video *before* it is needed, once the current one is nearly over. */
    private fun maybePrefetch() {
        if (prefetched != null || prefetchJob?.isActive == true) return
        if (!graph.settings.current.autoplayNext) return
        if (!player.playWhenReady || player.playbackState != Player.STATE_READY) return
        val duration = player.duration
        if (duration <= 0L) return
        if (duration - player.currentPosition > PREFETCH_WINDOW_MS) return

        val current = player.currentMediaItem?.readPlaybackBundle() ?: return
        val next = nextRelated(current) ?: return
        prefetchJob = graph.scope.launch {
            runCatching { graph.resolver.resolve(next.watchUrl) }
                .onSuccess { resolved ->
                    prefetched = resolved
                    L.d(TAG) { "prefetched ${next.id} (${resolved.candidates.size} rungs)" }
                }
                .onFailure { L.d(TAG) { "prefetch of ${next.id} failed: $it" } }
        }
    }

    private fun nextRelated(bundle: PlaybackBundle) = bundle.related.firstOrNull { candidate ->
        candidate.id.isNotEmpty() && candidate.id != bundle.videoId && candidate.id !in playedIds
    }

    // ---------------------------------------------------------------- housekeeping

    /**
     * One lightweight loop handles both jobs a playing session needs: remembering where the user
     * is, and warming up the next video. It runs at 0.2 Hz — this is housekeeping, not rendering.
     */
    private fun startMonitor() {
        monitorJob = graph.scope.launch(Dispatchers.Main.immediate) {
            while (isActive) {
                delay(MONITOR_INTERVAL_MS)
                if (player.playWhenReady) {
                    persistPosition()
                    maybePrefetch()
                }
            }
        }
    }

    private fun persistPosition() {
        val id = player.currentMediaItem?.mediaId ?: return
        val position = player.currentPosition
        if (position > 0L) graph.positions.put(id, position)
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            /* requestCode = */ 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun watchUrl(videoId: String) = "https://www.youtube.com/watch?v=$videoId"

    private companion object {
        const val TAG = "PlaybackService"
        const val SESSION_ID = "youtube-lite"
        const val NOTIFICATION_CHANNEL_ID = "playback"
        const val NOTIFICATION_ID = 1001

        /** How many rungs we may walk down on failures before re-resolving. */
        const val MAX_RUNG_HOPS = 4

        /** Fresh extractions allowed per recover() cycle before the user is told. */
        const val MAX_REFRESHES = 2

        const val MONITOR_INTERVAL_MS = 5_000L

        /** Start resolving the next video once this little remains of the current one. */
        const val PREFETCH_WINDOW_MS = 25_000L

        const val MAX_HISTORY = 20
    }
}
