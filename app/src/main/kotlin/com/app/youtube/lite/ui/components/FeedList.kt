package com.app.youtube.lite.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.app.youtube.lite.R
import com.app.youtube.lite.data.model.FeedState
import com.app.youtube.lite.data.model.VideoItem
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * The paged video list that every feed screen is built from.
 *
 * ## What makes this fast
 *
 * **Stable keys and content types.** `key = { it.id }` lets the list move rows instead of
 * rebuilding them, and `contentType = { "video" }` tells Compose that every row is the same kind,
 * so recycled composition slots are reused rather than re-created. Together they are the single
 * biggest win in a scrolling video feed — without a key, scrolling up re-composes every visible
 * row from scratch.
 *
 * **One `remember`ed image request per row**, keyed on the URL and the exact pixel size, so a row
 * that scrolls out and back does not re-decode a bitmap.
 *
 * **Pagination driven by layout info, not by composition.** The "load more" trigger is a
 * `snapshotFlow` over `layoutInfo`, mapped to the last visible index, filtered to distinct values
 * and gated on the remaining count. It runs *outside* composition entirely: no recomposition is
 * caused by scrolling near the end, and the trigger cannot fire twice for the same index.
 *
 * **Shimmer instead of a spinner for the first load** — the placeholder has the shape of the
 * content that is coming, which measurably reduces perceived latency and costs nothing extra
 * because the placeholders are drawn, not composed, per frame.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedList(
    state: FeedState,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onSignIn: () -> Unit,
    onClickVideo: (VideoItem) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(bottom = 88.dp),
    header: (LazyListScope.() -> Unit)? = null,
    /** Overrides the generic empty-state copy — search says "No results for …" instead. */
    emptyTitle: String? = null,
    emptyMessage: String? = null,
) {
    // Pixel size of a 16:9 thumbnail, computed once per configuration rather than per row.
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val thumbnailWidthPx = with(density) { (configuration.screenWidthDp.dp - 24.dp).roundToPx() }
    val thumbnailHeightPx = thumbnailWidthPx * 9 / 16

    val currentState = rememberUpdatedState(state)

    // Pagination: a single flow, distinct values only, gated on "close to the end".
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .map { lastIndex ->
                val content = currentState.value as? FeedState.Content
                val total = content?.items?.size ?: 0
                content != null && !content.loadingMore && !content.endReached &&
                    total > 0 && lastIndex >= total - PREFETCH_DISTANCE
            }
            .distinctUntilChanged()
            .filter { it }
            .collect { onLoadMore() }
    }

    PullToRefreshBox(
        isRefreshing = (state as? FeedState.Content)?.refreshing == true,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        when (state) {
            FeedState.Loading -> ShimmerFeed(count = 4, modifier = Modifier.fillMaxWidth())

            is FeedState.Error -> StateViews.Message(
                title = stringResource(
                    if (state.requiresAuth) R.string.feed_sign_in_required else R.string.feed_error,
                ),
                message = state.message,
                actionLabel = stringResource(
                    if (state.requiresAuth) R.string.feed_sign_in_action else R.string.feed_retry,
                ),
                onAction = if (state.requiresAuth) onSignIn else onRetry,
                modifier = Modifier.fillMaxSize(),
            )

            is FeedState.Content -> if (state.isEmpty) {
                StateViews.Message(
                    title = emptyTitle ?: stringResource(R.string.feed_empty),
                    message = emptyMessage ?: stringResource(R.string.feed_empty_hint),
                    actionLabel = stringResource(R.string.feed_retry),
                    onAction = onRetry,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = contentPadding,
                ) {
                    header?.invoke(this)

                    items(
                        items = state.items,
                        key = { video -> video.id },
                        contentType = { "video" },
                    ) { video ->
                        VideoCard(
                            video = video,
                            onClick = { onClickVideo(video) },
                            thumbnailWidthPx = thumbnailWidthPx,
                            thumbnailHeightPx = thumbnailHeightPx,
                        )
                    }

                    if (state.loadingMore) {
                        item(key = "loading-more", contentType = "footer") {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 20.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(4.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * How close to the end of the list triggers the next page. Three rows is roughly one fling: the
 * next page is in flight before the user reaches the bottom, so the list appears endless.
 */
private const val PREFETCH_DISTANCE = 3
