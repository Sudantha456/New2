package com.app.youtube.lite.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Shimmering placeholders for every "first load" state.
 *
 * The interesting part is *where* the animation is read. `progress` is a
 * [androidx.compose.runtime.State] that is only ever dereferenced inside `drawBehind`, i.e. in the
 * draw phase. A shimmer that repaints at 60 Hz therefore invalidates **only** the draw pass: no
 * recomposition, no re-measure, no new lambda instances. On a feed showing six placeholder cards,
 * that is the difference between a loading screen that costs a few hundred microseconds per frame
 * and one that costs several milliseconds — the exact budget this app refuses to spend on
 * something nobody is looking at yet.
 *
 * Colours come from the theme's container roles, so the placeholders adapt to light/dark without a
 * hard-coded grey.
 */
@Composable
fun ShimmerBlock(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(6.dp),
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SHIMMER_PERIOD_MS, easing = LinearEasing),
        ),
        label = "shimmer-progress",
    )
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val highlight = MaterialTheme.colorScheme.surfaceContainerHighest

    Box(
        modifier
            .clip(shape)
            .drawBehind {
                val sweep = progress.value
                val travel = size.width * 2f
                val head = -size.width + sweep * travel
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(base, highlight, base),
                        start = Offset(head, 0f),
                        end = Offset(head + size.width * 0.8f, size.height),
                    ),
                )
            },
    )
}

/** One placeholder row: 16:9 thumbnail, avatar, two title lines and a metadata line. */
@Composable
fun ShimmerVideoCard(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        ShimmerBlock(
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            shape = RoundedCornerShape(12.dp),
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Top) {
            ShimmerBlock(Modifier.size(36.dp), CircleShape)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ShimmerBlock(Modifier.fillMaxWidth(0.92f).height(14.dp))
                ShimmerBlock(Modifier.fillMaxWidth(0.60f).height(14.dp))
                Spacer(Modifier.height(2.dp))
                ShimmerBlock(Modifier.fillMaxWidth(0.45f).height(11.dp))
            }
        }
    }
}

/** A screenful of placeholder rows, for the first load of any feed. */
@Composable
fun ShimmerFeed(count: Int = 4, modifier: Modifier = Modifier) {
    Column(modifier) {
        repeat(count) { ShimmerVideoCard() }
    }
}

private const val SHIMMER_PERIOD_MS = 1_100
