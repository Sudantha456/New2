package com.app.youtube.lite.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.app.youtube.lite.R
import com.app.youtube.lite.player.PlayerUiState
import com.app.youtube.lite.ui.icons.LiteIcons

/**
 * The bar that keeps playback visible (and controllable) while the user browses.
 *
 * It is deliberately *not* a second player: no surface, no decoder, no media state of its own.
 * It renders [PlayerUiState] and sends commands back to the same `MediaController` the full-screen
 * player uses — so pausing from the mini player pauses the same session the notification and the
 * lock screen are driving.
 *
 * The whole bar is one `clickable`, with the two icon buttons on top: a tap anywhere expands to
 * the full player, which is what people actually try to do with a mini player.
 */
@Composable
fun MiniPlayer(
    state: PlayerUiState,
    onExpand: () -> Unit,
    onTogglePlay: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.hasMedia) return
    val context = LocalContext.current

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.fillMaxWidth()) {
            // The progress line sits at the very top edge, so it reads as the video's timeline
            // without stealing a row of its own.
            LinearProgressIndicator(
                progress = { state.progressFraction },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onExpand)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.audioOnly || state.thumbnailUrl == null) {
                        Icon(
                            imageVector = LiteIcons.Headphones,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                    } else {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(state.thumbnailUrl)
                                .size(88, 88)
                                .allowHardware(true)
                                .crossfade(false)
                                .build(),
                            contentDescription = null,
                            modifier = Modifier.size(44.dp),
                        )
                    }
                }

                Spacer(Modifier.width(10.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = state.channel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                IconButton(onClick = onTogglePlay) {
                    Icon(
                        imageVector = if (state.isPlaying) LiteIcons.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(
                            if (state.isPlaying) R.string.player_pause else R.string.player_play,
                        ),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.action_close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
