package com.app.youtube.lite.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import com.app.youtube.lite.R
import com.app.youtube.lite.core.util.Formatters
import com.app.youtube.lite.data.model.VideoItem

/**
 * One feed row.
 *
 * Every decision here is about what the card does *not* do:
 *
 *  • **No elevation, no shadow, no `Card`.** A Material card is a Surface with a graphics layer
 *    each; in a list of 120 rows that is 120 extra layers for a visual effect nobody notices on a
 *    black background.
 *  • **No channel avatar image.** The avatar is a coloured circle with the channel's initial,
 *    derived from the name. Loading real avatars would add one network request — and one bitmap —
 *    per row; the initial carries the same information at zero cost.
 *  • **Thumbnails are decoded to the exact on-screen size** ([Precision.EXACT]) as
 *    `Bitmap.Config.HARDWARE`. A 480 px thumbnail decoded at 1080 px wastes ~4 MB of Java heap;
 *    hardware bitmaps live only in the GPU, are never copied into the app heap, and are freed by
 *    the system under pressure without an OOM risk.
 *  • **Text is capped**: two lines for the title, one for the metadata. Text layout is the most
 *    expensive thing a list row does, and it scales with the number of lines.
 */
@Composable
fun VideoCard(
    video: VideoItem,
    onClick: () -> Unit,
    thumbnailWidthPx: Int,
    thumbnailHeightPx: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // Keyed so a recycled row does not rebuild (or re-decode) when it scrolls back into view.
    val request = remember(video.thumbnailUrl, thumbnailWidthPx, thumbnailHeightPx) {
        ImageRequest.Builder(context)
            .data(video.thumbnailUrl)
            .size(thumbnailWidthPx, thumbnailHeightPx)
            .precision(Precision.EXACT)
            .allowHardware(true)
            .crossfade(false)
            .memoryCacheKey(video.thumbnailUrl)
            .build()
    }

    Column(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (video.isLive) {
                Badge(
                    text = stringResource(R.string.video_live),
                    background = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp),
                )
            } else if (video.durationSeconds > 0L) {
                Badge(
                    text = Formatters.duration(video.durationSeconds * 1000L),
                    background = Color(0xCC000000),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp),
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.Top) {
            ChannelInitial(video.channel)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = metadata(
                        video = video,
                        viewsText = stringResource(
                            R.string.video_views,
                            Formatters.count(video.viewCount),
                        ),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * `"Channel · 1.2M views · 3 days ago"`, assembled with as few allocations as possible — this
 * string is built for every row that becomes visible, and `StringBuilder` with a known capacity
 * beats string templates with conditionals.
 */
private fun metadata(video: VideoItem, viewsText: String): String {
    val builder = StringBuilder(video.channel.length + viewsText.length + 24)
    if (video.channel.isNotEmpty()) builder.append(video.channel)
    if (video.viewCount > 0L) {
        if (builder.isNotEmpty()) builder.append(" · ")
        builder.append(viewsText)
    }
    val published = video.publishedText
    if (!published.isNullOrEmpty()) {
        if (builder.isNotEmpty()) builder.append(" · ")
        builder.append(published)
    }
    return builder.toString()
}

@Composable
private fun ChannelInitial(channel: String) {
    // Deterministic colour per channel: a stable hash into a small palette means the same channel
    // always gets the same circle across screens and sessions, with no state and no image load.
    val initial = channel.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: '•'
    val paletteIndex = ((channel.hashCode() % AVATAR_COLORS.size) + AVATAR_COLORS.size) % AVATAR_COLORS.size
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(AVATAR_COLORS[paletteIndex]),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial.toString(),
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
        )
    }
}

@Composable
private fun Badge(
    text: String,
    background: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(background)
            .padding(horizontal = 5.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

private val AVATAR_COLORS = listOf(
    Color(0xFFB71C1C),
    Color(0xFF1B5E20),
    Color(0xFF0D47A1),
    Color(0xFF4A148C),
    Color(0xFFE65100),
    Color(0xFF006064),
    Color(0xFF37474F),
    Color(0xFF880E4F),
)
