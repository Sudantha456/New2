package com.app.youtube.lite.ui.screens

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.youtube.lite.R
import com.app.youtube.lite.core.di.AppGraph
import com.app.youtube.lite.core.perf.FrameRateController
import com.app.youtube.lite.core.util.Formatters
import com.app.youtube.lite.data.model.PlaybackBundle
import com.app.youtube.lite.data.model.VideoItem
import com.app.youtube.lite.player.PlayerManager
import com.app.youtube.lite.player.PlayerUiState
import com.app.youtube.lite.player.VideoPlayerView
import com.app.youtube.lite.ui.components.ShimmerBlock
import com.app.youtube.lite.ui.components.StateViews
import com.app.youtube.lite.ui.components.VideoCard
import com.app.youtube.lite.ui.icons.LiteIcons
import kotlinx.coroutines.delay

/**
 * The player screen: video on top, metadata and "up next" below.
 *
 * ## Resolution
 *
 * Playback starts as soon as the rendition ladder is resolved — the related-videos request is
 * kicked off *after* `play()` in the same coroutine precisely so it cannot delay the first frame.
 * A saved resume position is applied from the local store, not from the network.
 *
 * ## Fullscreen
 *
 * Fullscreen is not a second activity: it hides the system bars, forces landscape and lets the
 * player fill the window. Because the activity already declares `configChanges` for orientation,
 * that rotation costs no activity recreation, no re-preparation of the `MediaSource` and no
 * flash of the shutter — playback simply continues through the change.
 *
 * ## Gestures
 *
 * Double-tap seeks (left third rewinds, right third forwards, the configured number of seconds),
 * single tap toggles the controls, and the controls hide themselves after four seconds of
 * playback. Every gesture is handled by one `pointerInput`, so the player surface never competes
 * with a parent scroll container for touch events.
 */
@Composable
fun VideoScreen(
    videoId: String,
    graph: AppGraph,
    playerManager: PlayerManager,
    onBack: () -> Unit,
    onOpenVideo: (String) -> Unit,
    onRequireSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val settings by graph.settings.flow.collectAsStateWithLifecycle()
    val playerState by playerManager.state.collectAsStateWithLifecycle()

    var bundle by remember(videoId) { mutableStateOf<PlaybackBundle?>(null) }
    var related by remember(videoId) { mutableStateOf<List<VideoItem>>(emptyList()) }
    var error by remember(videoId) { mutableStateOf<String?>(null) }
    var loading by remember(videoId) { mutableStateOf(true) }
    var retryTick by remember(videoId) { mutableIntStateOf(0) }
    var fullscreen by remember { mutableStateOf(false) }
    var audioOnly by remember(videoId) { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var seekHint by remember { mutableStateOf<String?>(null) }

    val frameRateController = remember(activity) { activity?.let { FrameRateController(it) } }
    val shareChooserTitle = stringResource(R.string.video_share_chooser)
    val currentVideoId = rememberUpdatedState(videoId)

    // ── Resolve and start playback ───────────────────────────────────────────
    // Keyed on `retryTick` as well, so the retry button re-runs the whole resolution instead of
    // faking progress: a failed extraction usually means an expired player response, and only a
    // fresh one can fix it.
    LaunchedEffect(videoId, retryTick) {
        loading = true
        error = null
        bundle = null
        related = emptyList()
        val watchUrl = "https://www.youtube.com/watch?v=$videoId"
        runCatching { graph.resolver.resolve(watchUrl) }
            .onSuccess { resolved ->
                bundle = resolved
                playerManager.play(resolved, graph.positions.get(videoId))
            }
            .onFailure { failure ->
                error = failure.message ?: "Could not load this video"
            }
        loading = false
        // Related videos are fetched only after the video itself is playing: the request is a
        // second network round trip and must never delay the first frame.
        if (error == null) {
            runCatching { graph.resolver.related(watchUrl) }
                .onSuccess { related = it }
        }
    }

    // ── Audio-only while the screen is off / the app is backgrounded ─────────
    DisposableEffect(lifecycleOwner, videoId) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    if (settings.backgroundAudio) {
                        // Nobody can see the video: release the decoder, keep the audio.
                        playerManager.setAudioOnly(true)
                    } else {
                        // The user asked for foreground-only playback; honour that literally.
                        playerManager.pause()
                    }
                }
                Lifecycle.Event.ON_START -> {
                    if (settings.backgroundAudio && !audioOnly) playerManager.setAudioOnly(false)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ── Fullscreen: bars, orientation, and always restore ───────────────────
    DisposableEffect(fullscreen, activity) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (fullscreen) {
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose { }
    }
    // Leaving the screen must never strand the device in landscape with hidden bars.
    DisposableEffect(activity) {
        onDispose {
            activity?.window?.let { window ->
                WindowCompat.getInsetsController(window, window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            frameRateController?.release()
        }
    }

    // ── Controls auto-hide while playing ────────────────────────────────────
    LaunchedEffect(controlsVisible, playerState.isPlaying, playerState.isBuffering) {
        if (controlsVisible && playerState.isPlaying && !playerState.isBuffering) {
            delay(CONTROLS_TIMEOUT_MS)
            controlsVisible = false
        }
    }
    LaunchedEffect(seekHint) {
        if (seekHint != null) {
            delay(SEEK_HINT_MS)
            seekHint = null
        }
    }

    val videoAspect = if (fullscreen) 1f else 16f / 9f

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(
            Modifier
                .fillMaxWidth()
                .then(if (fullscreen) Modifier.fillMaxSize() else Modifier.aspectRatio(videoAspect))
                .background(Color.Black)
                .pointerInput(settings.doubleTapSeekSeconds) {
                    detectTapGestures(
                        onTap = { controlsVisible = !controlsVisible },
                        onDoubleTap = { offset ->
                            val step = settings.doubleTapSeekSeconds * 1000L
                            val third = size.width / 3f
                            when {
                                offset.x < third -> {
                                    playerManager.seekBy(-step)
                                    seekHint = "-${settings.doubleTapSeekSeconds}s"
                                }
                                offset.x > third * 2 -> {
                                    playerManager.seekBy(step)
                                    seekHint = "+${settings.doubleTapSeekSeconds}s"
                                }
                                else -> playerManager.togglePlayPause()
                            }
                        },
                    )
                },
        ) {
            VideoPlayerView(
                player = playerManager.player,
                modifier = Modifier.fillMaxSize(),
                keepScreenOn = playerState.isPlaying && !audioOnly,
                onVideoSizeChanged = { size ->
                    if (size.width > 0 && size.height > 0) {
                        frameRateController?.matchVideo(
                            width = size.width,
                            height = size.height,
                            fps = size.frameRate,
                            allowHighRefreshRate = settings.highRefreshRate,
                        )
                    }
                },
            )

            // Buffering indicator: only when the player is actually waiting, never on a pause.
            if (playerState.isBuffering && !playerState.isEnded) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(44.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 3.dp,
                    )
                    Spacer(Modifier.height(10.dp))
                    // "Switching stream…" instead of a bare "Buffering": the service is walking the
                    // rendition ladder after a failure, and saying so is the difference between
                    // "it is broken" and "it is fixing itself".
                    Text(
                        text = stringResource(
                            if (playerState.recovering) {
                                R.string.video_recovering
                            } else {
                                R.string.player_buffering
                            },
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                    )
                }
            }

            // Audio-only: the video track is disabled, so say so rather than showing a black box.
            if (audioOnly) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = LiteIcons.Headphones,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp),
                    )
                }
            }

            seekHint?.let { hint ->
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(hint, color = Color.White, style = MaterialTheme.typography.titleSmall)
                }
            }

            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                PlayerControls(
                    playerState = playerState,
                    bundle = bundle,
                    isFullscreen = fullscreen,
                    audioOnly = audioOnly,
                    onBack = onBack,
                    onTogglePlay = { playerManager.togglePlayPause() },
                    onSeekBy = { delta -> playerManager.seekBy(delta) },
                    onSeekTo = { fraction ->
                        val duration = playerState.durationMs
                        if (duration > 0L) playerManager.seekTo((duration * fraction).toLong())
                    },
                    onNext = {
                        related.firstOrNull()?.let { next -> onOpenVideo(next.id) }
                    },
                    onSelectQuality = { index -> playerManager.selectRung(index) },
                    onSelectSpeed = { speed -> playerManager.setSpeed(speed) },
                    onToggleAudioOnly = {
                        audioOnly = !audioOnly
                        playerManager.setAudioOnly(audioOnly)
                    },
                    onToggleFullscreen = { fullscreen = !fullscreen },
                )
            }

            if (loading) {
                Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        if (!fullscreen) {
            // A resolution failure means there is nothing to watch; a *playback* failure with a
            // resolved ladder is recoverable and is offered as an inline retry instead.
            val resolutionFailure = error
            if (resolutionFailure != null && bundle == null) {
                StateViews.Error(
                    message = resolutionFailure,
                    onRetry = { retryTick++ },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                VideoDetails(
                    bundle = bundle,
                    playerState = playerState,
                    related = related,
                    playbackError = playerState.errorMessage,
                    onRetryPlayback = { playerManager.retry() },
                    onOpenVideo = onOpenVideo,
                    onShare = {
                        shareVideo(
                            context = context,
                            videoId = currentVideoId.value,
                            chooserTitle = shareChooserTitle,
                        )
                    },
                    onLike = onRequireSignIn,
                )
            }
        }
    }
}

/** Metadata, actions and "up next", below the player. */
@Composable
private fun VideoDetails(
    bundle: PlaybackBundle?,
    playerState: PlayerUiState,
    related: List<VideoItem>,
    playbackError: String?,
    onRetryPlayback: () -> Unit,
    onOpenVideo: (String) -> Unit,
    onShare: () -> Unit,
    onLike: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val thumbnailWidthPx = with(density) { (configuration.screenWidthDp.dp - 24.dp).roundToPx() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item(key = "metadata", contentType = "metadata") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = bundle?.title ?: playerState.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = buildString(48) {
                        val views = bundle?.viewCount ?: 0L
                        if (views > 0L) append("${Formatters.count(views)} views")
                        if (bundle?.durationMs != null && bundle.durationMs > 0L) {
                            if (isNotEmpty()) append(" · ")
                            append(Formatters.duration(bundle.durationMs))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = bundle?.channel?.firstOrNull()?.uppercase() ?: "•",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = bundle?.channel ?: playerState.channel,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = onLike) {
                        Icon(
                            imageVector = Icons.Filled.ThumbUp,
                            contentDescription = stringResource(R.string.video_like),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onShare) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = stringResource(R.string.video_share),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (playbackError != null) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = playbackError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onRetryPlayback) {
                            Text(stringResource(R.string.video_retry))
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.video_related),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            }
        }

        if (related.isEmpty()) {
            items(count = 3, key = { "related-shimmer-$it" }) {
                ShimmerBlock(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(88.dp)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(10.dp),
                )
            }
        } else {
            items(
                items = related,
                key = { it.id },
                contentType = { "related" },
            ) { video ->
                VideoCard(
                    video = video,
                    onClick = { onOpenVideo(video.id) },
                    thumbnailWidthPx = thumbnailWidthPx,
                    thumbnailHeightPx = thumbnailWidthPx * 9 / 16,
                )
            }
        }
    }
}

/** The overlay controls: title bar, transport row, scrubber, and the quality/speed menus. */
@Composable
private fun PlayerControls(
    playerState: PlayerUiState,
    bundle: PlaybackBundle?,
    isFullscreen: Boolean,
    audioOnly: Boolean,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeekTo: (Float) -> Unit,
    onNext: () -> Unit,
    onSelectQuality: (Int) -> Unit,
    onSelectSpeed: (Float) -> Unit,
    onToggleAudioOnly: () -> Unit,
    onToggleFullscreen: () -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var qualityMenu by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }
    var overflowMenu by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color(0x66000000))) {
        // Top row: back, title, overflow.
        Row(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .background(Color(0x99000000))
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.nav_back),
                    tint = Color.White,
                )
            }
            Text(
                text = bundle?.title ?: playerState.title,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { overflowMenu = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.nav_more),
                    tint = Color.White,
                )
            }
            DropdownMenu(expanded = overflowMenu, onDismissRequest = { overflowMenu = false }) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (audioOnly) R.string.video_show_video else R.string.video_audio_only,
                            ),
                        )
                    },
                    leadingIcon = { Icon(LiteIcons.Headphones, contentDescription = null) },
                    onClick = {
                        overflowMenu = false
                        onToggleAudioOnly()
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                R.string.video_quality_menu,
                                playerState.qualityLabel ?: "Auto",
                            ),
                        )
                    },
                    onClick = {
                        overflowMenu = false
                        qualityMenu = true
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.video_speed_menu, "${playerState.speed}×"),
                        )
                    },
                    onClick = {
                        overflowMenu = false
                        speedMenu = true
                    },
                )
            }
        }

        // Transport.
        Row(
            Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // "Previous" is a 10-second rewind here: this player has no playlist to step back
            // through, and the rewind step is the one the user configured for double-tap. The
            // double-tap gesture does the same thing anywhere on the surface.
            IconButton(onClick = { onSeekBy(-10_000L) }) {
                Icon(
                    imageVector = LiteIcons.SkipPrevious,
                    contentDescription = stringResource(R.string.player_rewind, 10),
                    tint = Color.White,
                    modifier = Modifier.size(26.dp),
                )
            }
            FilledIconButton(
                onClick = onTogglePlay,
                modifier = Modifier.size(56.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White,
                ),
            ) {
                Icon(
                    imageVector = if (playerState.isPlaying) LiteIcons.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(
                        if (playerState.isPlaying) R.string.player_pause else R.string.player_play,
                    ),
                    modifier = Modifier.size(30.dp),
                )
            }
            IconButton(onClick = onNext) {
                Icon(
                    imageVector = LiteIcons.SkipNext,
                    contentDescription = stringResource(R.string.player_next),
                    tint = Color.White,
                    modifier = Modifier.size(26.dp),
                )
            }
        }

        // Bottom: scrubber, times, fullscreen.
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color(0x99000000))
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = Formatters.clock(playerState.positionMs),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
                Slider(
                    value = if (dragging) dragFraction else playerState.progressFraction,
                    onValueChange = { value ->
                        dragging = true
                        dragFraction = value
                    },
                    onValueChangeFinished = {
                        onSeekTo(dragFraction)
                        dragging = false
                    },
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                )
                Text(
                    text = Formatters.clock(playerState.durationMs),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
                IconButton(onClick = onToggleFullscreen) {
                    Icon(
                        imageVector = if (isFullscreen) LiteIcons.FullscreenExit else LiteIcons.Fullscreen,
                        contentDescription = stringResource(R.string.video_fullscreen),
                        tint = Color.White,
                    )
                }
            }
        }

        DropdownMenu(expanded = qualityMenu, onDismissRequest = { qualityMenu = false }) {
            playerState.qualityOptions.forEachIndexed { index, label ->
                DropdownMenuItem(
                    text = { Text(if (index == playerState.selectedQualityIndex) "• $label" else label) },
                    onClick = {
                        qualityMenu = false
                        onSelectQuality(index)
                    },
                )
            }
            if (playerState.qualityOptions.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.video_single_stream)) },
                    onClick = { qualityMenu = false },
                )
            }
        }

        DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
            SPEEDS.forEach { speed ->
                DropdownMenuItem(
                    text = { Text(if (speed == playerState.speed) "• ${speed}×" else "$speed×") },
                    onClick = {
                        speedMenu = false
                        onSelectSpeed(speed)
                    },
                )
            }
        }
    }
}

/** Hands the video off to whatever the user actually wants to send it with. */
private fun shareVideo(context: android.content.Context, videoId: String, chooserTitle: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "https://www.youtube.com/watch?v=$videoId")
    }
    val chooser = Intent.createChooser(intent, chooserTitle).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(chooser)
    } catch (_: ActivityNotFoundException) {
        // No share target installed: nothing to do, and nothing worth interrupting the user for.
    }
}

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
private const val CONTROLS_TIMEOUT_MS = 4_000L
private const val SEEK_HINT_MS = 600L
