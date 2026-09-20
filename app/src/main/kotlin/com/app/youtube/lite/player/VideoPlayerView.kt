package com.app.youtube.lite.player

import android.view.LayoutInflater
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.ui.PlayerView
import com.app.youtube.lite.R

/**
 * The video surface, hosted in Compose.
 *
 * Compose has no way to host a `SurfaceView` from a `Player` directly, so the industry-standard
 * bridge is `AndroidView` around `PlayerView`. The XML it inflates
 * (`res/layout/view_player.xml`) pins the two things that matter for performance:
 * `surface_type="surface_view"` — a real hardware-composited surface rather than a TextureView —
 * and `use_controller="false"`, because the controls are Compose.
 *
 * ## Decoders are released when nobody is looking
 *
 * A video decoder holds a large, scarce resource: an output buffer queue, a secure display path
 * and (on many SoCs) an extra copy of the frame plus the display's own memory. It is therefore
 * attached to a surface **only while the surface is actually visible**:
 *
 *  • `Lifecycle.ON_PAUSE`/`ON_STOP` (screen off, app backgrounded, another activity on top)
 *    detaches the player from the view — ExoPlayer releases the codec's surface and stops
 *    producing frames, while audio keeps playing through `PlaybackService`;
 *  • `ON_START` re-attaches it, which re-acquires the decoder at the exact position playback
 *    reached — no reload, no re-buffer, because the media source never changed;
 *  • when the composable leaves the composition the surface is detached for good.
 *
 * Combined with `PlayerFactory.setAudioOnly(true)` (video track disabled outright, used when the
 * user backgrounds the app with background audio enabled) this is what keeps an actively playing
 * session inside the RAM/battery budget instead of decoding into a surface nobody can see.
 */
@Composable
fun VideoPlayerView(
    player: Player?,
    modifier: Modifier = Modifier,
    /** Screen-on is what makes "watching" different from "listening"; callers pass false for audio-only. */
    keepScreenOn: Boolean = true,
    /** Fired on every size/rotation change; used to drive the refresh-rate request and artwork layout. */
    onVideoSizeChanged: ((VideoSize) -> Unit)? = null,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val holder = remember { PlayerViewHolder() }
    // The callback is held in a state, not used as a key: a caller passing a lambda literal would
    // otherwise hand us a *new* instance on every recomposition, restarting the effect (and
    // re-registering the player listener) dozens of times per second.
    val currentSizeCallback by rememberUpdatedState(onVideoSizeChanged)

    AndroidView(
        modifier = modifier,
        factory = { context ->
            (LayoutInflater.from(context).inflate(R.layout.view_player, null) as PlayerView).also {
                it.player = player
                holder.view = it
            }
        },
        update = { view ->
            view.keepScreenOn = keepScreenOn && player != null
            if (view.player !== player) view.player = player
        },
    )

    // Size changes are reported by the player, not the view: ExoPlayer knows the real video size
    // (including the pixel aspect ratio) as soon as the first frame is parsed.
    DisposableEffect(player) {
        val callback = currentSizeCallback
        val listener = if (callback == null) {
            null
        } else {
            object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    currentSizeCallback?.invoke(videoSize)
                }
            }.also { player?.addListener(it) }
        }
        onDispose { listener?.let { player?.removeListener(it) } }
    }

    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // Detach: the codec's surface is released, the decoder goes idle, audio continues.
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> holder.view?.player = null
                // Re-attach at whatever position playback reached while nobody was watching.
                Lifecycle.Event.ON_START -> holder.view?.let { if (it.player !== player) it.player = player }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            holder.view?.player = null
            holder.view = null
        }
    }
}

/** Tiny mutable holder so the `AndroidView` instance can be reached from `DisposableEffect`s. */
private class PlayerViewHolder {
    var view: PlayerView? = null
}
