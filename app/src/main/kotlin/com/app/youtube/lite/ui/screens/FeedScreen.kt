package com.app.youtube.lite.ui.screens

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.youtube.lite.core.di.AppGraph
import com.app.youtube.lite.data.model.FeedKey
import com.app.youtube.lite.data.model.VideoItem
import com.app.youtube.lite.ui.components.FeedList
import kotlinx.coroutines.launch

/**
 * One feed, whichever endpoint it comes from.
 *
 * Home, Subscriptions, Library, History and Liked videos are all the *same* screen with a
 * different [FeedKey]: `browseId` + `params` fully describe the request, the paging cursor and the
 * auth requirement, so there is no reason to write five screens that differ only in a string.
 *
 * The lifecycle of a load is:
 *  1. `ensureLoaded` — fetch only if this feed has never been loaded in this session, so switching
 *     tabs costs nothing and never refetches what is already on screen;
 *  2. `refresh` — a pull-to-refresh, which replaces the list but keeps the screen's scroll state;
 *  3. `loadMore` — driven by [FeedList]'s layout-info watcher, which is what makes scrolling
 *     endless without any visible "page 2" seam.
 */
@Composable
fun FeedScreen(
    graph: AppGraph,
    feedKey: FeedKey,
    onClickVideo: (VideoItem) -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    // `observe` returns the session's stable StateFlow; remembering it keyed on the feed keeps the
    // collection from being rebuilt when this screen recomposes for unrelated reasons.
    val flow = remember(feedKey) { graph.feeds.observe(feedKey) }
    val state = flow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    LaunchedEffect(feedKey) { graph.feeds.ensureLoaded(feedKey) }

    FeedList(
        state = state.value,
        onRefresh = { scope.launch { graph.feeds.refresh(feedKey) } },
        onRetry = { scope.launch { graph.feeds.refresh(feedKey) } },
        onLoadMore = { scope.launch { graph.feeds.loadMore(feedKey) } },
        onSignIn = onSignIn,
        onClickVideo = onClickVideo,
        listState = listState,
        modifier = modifier,
    )
}
