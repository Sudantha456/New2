package com.app.youtube.lite.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.app.youtube.lite.R
import com.app.youtube.lite.core.di.AppGraph
import com.app.youtube.lite.data.innertube.parseContinuation
import com.app.youtube.lite.data.innertube.parseItems
import com.app.youtube.lite.data.model.FeedState
import com.app.youtube.lite.data.model.VideoItem
import com.app.youtube.lite.ui.components.FeedList
import com.app.youtube.lite.ui.components.StateViews
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Search, debounced and paged.
 *
 * Three details make this feel instant rather than merely correct:
 *
 *  • **Debounce.** Every keystroke restarts a 350 ms timer; only the last one is sent. Typing
 *    "the office bloopers" issues one request instead of twenty, which matters both for the
 *    network and for YouTube's rate limiting.
 *  • **Cancellation.** The search runs inside a `LaunchedEffect` keyed on the query, so the
 *    previous request is cancelled the moment the query changes — no out-of-order responses
 *    overwriting fresh results with stale ones.
 *  • **The same list engine as the feeds.** Results are rendered by [FeedList], which means the
 *    search results inherit stable keys, exact-size hardware thumbnails and layout-driven paging
 *    for free — including the continuation cursor, which YouTube returns like any other feed.
 */
@Composable
fun SearchScreen(
    graph: AppGraph,
    onClickVideo: (VideoItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    var items by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var continuation by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var endReached by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(submitted) {
        val term = submitted.trim()
        if (term.isEmpty()) {
            items = emptyList()
            continuation = null
            error = null
            endReached = false
            return@LaunchedEffect
        }
        loading = true
        error = null
        runCatching { graph.client.search(term) }
            .onSuccess { page ->
                items = parseItems(page)
                continuation = parseContinuation(page)
                endReached = continuation == null
            }
            .onFailure { failure ->
                items = emptyList()
                error = failure.message ?: "Search failed"
            }
        loading = false
    }

    // Debounce: the query becomes "submitted" only after the user stops typing.
    LaunchedEffect(query) {
        val term = query.trim()
        if (term == submitted) return@LaunchedEffect
        // One character matches half of YouTube; the round trip would be wasted.
        if (term.length < MIN_QUERY_LENGTH) {
            submitted = ""
            return@LaunchedEffect
        }
        delay(DEBOUNCE_MS)
        submitted = term
        listState.animateScrollToItem(0)
    }

    val state: FeedState = when {
        loading -> FeedState.Loading
        error != null -> FeedState.Error(error.orEmpty())
        items.isEmpty() && submitted.isEmpty() -> FeedState.Content(items = emptyList(), endReached = true)
        else -> FeedState.Content(
            items = items,
            loadingMore = loadingMore,
            endReached = endReached,
        )
    }

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.search_hint)) },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                when {
                    loading -> CircularProgressIndicator(
                        modifier = Modifier.padding(6.dp),
                        strokeWidth = 2.dp,
                    )
                    query.isNotEmpty() -> IconButton(onClick = { query = "" }) {
                        Icon(
                            Icons.Filled.Clear,
                            contentDescription = stringResource(R.string.search_clear),
                        )
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    keyboard?.hide()
                    submitted = query.trim()
                },
            ),
        )

        if (submitted.isEmpty()) {
            StateViews.Message(
                title = stringResource(R.string.search_hint),
                message = stringResource(R.string.search_start_typing),
                icon = Icons.Filled.Search,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            FeedList(
                state = state,
                onRefresh = { submitted = query.trim() },
                onRetry = { submitted = query.trim() },
                onLoadMore = {
                    val cursor = continuation ?: return@FeedList
                    if (loadingMore) return@FeedList
                    loadingMore = true
                    scope.launch {
                        runCatching { graph.client.search(submitted, cursor) }
                            .onSuccess { page ->
                                val fresh = parseItems(page)
                                val seen = items.mapTo(HashSet(items.size + fresh.size)) { it.id }
                                items = items + fresh.filter { seen.add(it.id) }
                                continuation = parseContinuation(page)
                                endReached = continuation == null
                            }
                        loadingMore = false
                    }
                },
                onSignIn = { },
                onClickVideo = onClickVideo,
                listState = listState,
                emptyTitle = stringResource(R.string.search_no_results, submitted),
                emptyMessage = stringResource(R.string.search_start_typing),
                header = {
                    item(key = "results-header", contentType = "header") {
                        Text(
                            text = stringResource(R.string.search_results_for, submitted),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                        )
                    }
                },
            )
        }
    }
}

private const val DEBOUNCE_MS = 350L
private const val MIN_QUERY_LENGTH = 2
