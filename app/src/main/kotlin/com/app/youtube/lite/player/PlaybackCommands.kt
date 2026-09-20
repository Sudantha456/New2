package com.app.youtube.lite.player

import android.os.Bundle
import androidx.media3.session.SessionCommand

/**
 * Custom `MediaController` → `MediaSessionService` commands.
 *
 * These are the operations that cannot be expressed through the standard `Player` interface
 * but must still be executed by the *service* (the only owner of the `ExoPlayer`):
 *
 *  • [PLAY_RUNG] — set the candidate ladder for a video (the bundle itself rides in
 *    `PlaybackBundle.toArguments()`; the service picks rung `selectedIndex`, or the first rung
 *    it can actually open) and start playing it at the requested position,
 *  • [SELECT_RUNG] — switch to another rendition in the candidate ladder (manual quality pick,
 *    and the mechanism the automatic recovery uses when swapping a broken stream),
 *  • [SET_AUDIO_ONLY] — drop or restore the video track. Disabling `TRACK_TYPE_VIDEO` makes
 *    ExoPlayer release the video decoder and its surfaces, which is what makes background
 *    listening genuinely cheap rather than "video decoding into a black hole",
 *  • [RETRY] — clear a failed item and re-prepare from the current position.
 */
object PlaybackCommands {

    const val ACTION_PLAY_RUNG = "com.app.youtube.lite.action.PLAY_RUNG"
    const val ACTION_SELECT_RUNG = "com.app.youtube.lite.action.SELECT_RUNG"
    const val ACTION_SET_AUDIO_ONLY = "com.app.youtube.lite.action.SET_AUDIO_ONLY"
    const val ACTION_RETRY = "com.app.youtube.lite.action.RETRY"

    const val ARG_RUNG_INDEX = "rung_index"
    const val ARG_AUDIO_ONLY = "audio_only"
    const val ARG_START_POSITION_MS = "start_position_ms"

    val PLAY_RUNG = SessionCommand(ACTION_PLAY_RUNG, Bundle.EMPTY)
    val SELECT_RUNG = SessionCommand(ACTION_SELECT_RUNG, Bundle.EMPTY)
    val SET_AUDIO_ONLY = SessionCommand(ACTION_SET_AUDIO_ONLY, Bundle.EMPTY)
    val RETRY = SessionCommand(ACTION_RETRY, Bundle.EMPTY)

    val ALL: List<SessionCommand> = listOf(PLAY_RUNG, SELECT_RUNG, SET_AUDIO_ONLY, RETRY)
}
