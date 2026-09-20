package com.app.youtube.lite.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.youtube.lite.R
import com.app.youtube.lite.core.di.AppGraph
import com.app.youtube.lite.core.net.NetworkStatus
import com.app.youtube.lite.core.util.L
import com.app.youtube.lite.data.model.FeedKey
import com.app.youtube.lite.player.PlayerManager
import com.app.youtube.lite.ui.components.MiniPlayer
import com.app.youtube.lite.ui.components.StateViews
import com.app.youtube.lite.ui.icons.LiteIcons
import com.app.youtube.lite.ui.navigation.Screen
import com.app.youtube.lite.ui.navigation.rememberNavStack
import com.app.youtube.lite.ui.screens.FeedScreen
import com.app.youtube.lite.ui.screens.LoginScreen
import com.app.youtube.lite.ui.screens.SearchScreen
import com.app.youtube.lite.ui.screens.SettingsScreen
import com.app.youtube.lite.ui.screens.VideoScreen
import com.app.youtube.lite.ui.theme.LiteTheme
import kotlinx.coroutines.launch

/**
 * The app shell: theme, navigation, the bottom bar, the mini player and the top bar.
 *
 * Two structural decisions are worth stating:
 *
 *  • **The playback session outlives the screen.** [PlayerManager] is remembered here, at the
 *    root, and connected for as long as the composition lives — not created per player screen.
 *    That is what makes the mini player possible, and what lets the user navigate away from a
 *    video without interrupting it.
 *  • **Screens are swapped, not stacked.** Only one screen is composed at a time (`when`), so a
 *    background feed stops recomposing the moment the user opens a video. The scroll position of
 *    each feed survives because the `LazyListState`s are remembered per tab at this level.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiteAppShell(
    graph: AppGraph,
    deepLinkVideoId: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val nav = rememberNavStack()
    val snackbarHostState = remember { SnackbarHostState() }
    // Resolved here, because the handlers that use them are plain lambdas, not composable scopes.
    val signedInMessage = stringResource(R.string.auth_signed_in)
    val signInToLikeMessage = stringResource(R.string.player_sign_in_to_like)

    // One player session for the whole app; released only when this composition goes away.
    val playerManager = remember { PlayerManager(context, graph.positions, graph.scope) }
    val playerState by playerManager.state.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        playerManager.connect()
        onDispose { playerManager.release() }
    }

    // Deep links: a shared youtube.com/youtu.be URL opens straight into the player.
    LaunchedEffect(deepLinkVideoId) {
        if (!deepLinkVideoId.isNullOrEmpty()) nav.push(Screen.Player(deepLinkVideoId))
    }

    // Notification permission: without it the media notification is invisible on API 33+, and the
    // user loses lock-screen control of their own audio.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> L.i("Shell") { "notification permission granted=$granted" } }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Connectivity is observed once, here, and shown as a strip — no screen has to know about it.
    val networkStatus by graph.network.status.collectAsStateWithLifecycle(
        initialValue = NetworkStatus(),
    )

    // One scroll position per tab, hoisted above the screen switch: leaving a tab and coming back
    // must land the user exactly where they were, which is only possible if the state outlives the
    // screen's composition.
    val homeListState = rememberLazyListState()
    val subscriptionsListState = rememberLazyListState()
    val libraryListState = rememberLazyListState()

    BackHandler(enabled = nav.canGoBack) { nav.pop() }

    LiteTheme {
        val current = nav.current
        val showBottomBar = current !is Screen.Player && current !is Screen.Login
        val showMiniPlayer = current !is Screen.Player && playerState.hasMedia

        Scaffold(
            topBar = {
                if (current !is Screen.Player && current !is Screen.Login) {
                    TopAppBar(
                        title = { Text(titleFor(current)) },
                        actions = {
                            if (current is Screen.Settings) {
                                IconButton(onClick = { nav.push(Screen.Login) }) {
                                    Icon(
                                    Icons.Filled.AccountCircle,
                                    contentDescription = stringResource(R.string.nav_sign_in),
                                )
                                }
                            } else {
                                IconButton(onClick = { current.feedKey()?.let { key ->
                                    scope.launch { graph.feeds.refresh(key) }
                                } }) {
                                    Icon(
                                        Icons.Filled.Refresh,
                                        contentDescription = stringResource(R.string.action_refresh),
                                    )
                                }
                                IconButton(onClick = { nav.push(Screen.Settings) }) {
                                    Icon(
                                        Icons.Filled.Settings,
                                        contentDescription = stringResource(R.string.nav_settings),
                                    )
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            titleContentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                }
            },
            bottomBar = {
                Column {
                    if (showMiniPlayer) {
                        MiniPlayer(
                            state = playerState,
                            onExpand = {
                                playerState.mediaId?.let { nav.push(Screen.Player(it)) }
                            },
                            onTogglePlay = { playerManager.togglePlayPause() },
                            // Closing the mini player stops playback but keeps the session bound,
                            // so the next video starts instantly instead of re-binding a service.
                            onClose = { playerManager.stop() },
                        )
                    }
                    if (showBottomBar) {
                        BottomBar(current = current, onSelect = { tab -> nav.selectTab(tab) })
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = MaterialTheme.colorScheme.surface,
        ) { innerPadding ->
            Column(Modifier.fillMaxSize().padding(innerPadding)) {
                if (!networkStatus.online) {
                    StateViews.OfflineBanner()
                }
                Box(Modifier.fillMaxSize()) {
                    when (current) {
                        Screen.Home -> FeedScreen(
                            graph = graph,
                            feedKey = FeedKey.Home,
                            onClickVideo = { video -> nav.push(Screen.Player(video.id)) },
                            onSignIn = { nav.push(Screen.Login) },
                            listState = homeListState,
                        )

                        Screen.Subscriptions -> FeedScreen(
                            graph = graph,
                            feedKey = FeedKey.Subscriptions,
                            onClickVideo = { video -> nav.push(Screen.Player(video.id)) },
                            onSignIn = { nav.push(Screen.Login) },
                            listState = subscriptionsListState,
                        )

                        Screen.Library -> FeedScreen(
                            graph = graph,
                            feedKey = FeedKey.Library,
                            onClickVideo = { video -> nav.push(Screen.Player(video.id)) },
                            onSignIn = { nav.push(Screen.Login) },
                            listState = libraryListState,
                        )

                        Screen.Search -> SearchScreen(
                            graph = graph,
                            onClickVideo = { video -> nav.push(Screen.Player(video.id)) },
                        )

                        is Screen.Player -> VideoScreen(
                            videoId = current.videoId,
                            graph = graph,
                            playerManager = playerManager,
                            onBack = { nav.pop() },
                            onOpenVideo = { id -> nav.push(Screen.Player(id)) },
                            onRequireSignIn = {
                                scope.launch {
                                    snackbarHostState.showSnackbar(signInToLikeMessage)
                                }
                            },
                        )

                        Screen.Settings -> SettingsScreen(
                            graph = graph,
                            onSignIn = { nav.push(Screen.Login) },
                        )

                        Screen.Login -> LoginScreen(
                            graph = graph,
                            onSignedIn = {
                                graph.feeds.invalidateAll()
                                nav.pop()
                                scope.launch { snackbarHostState.showSnackbar(signedInMessage) }
                            },
                            onCancel = { nav.pop() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomBar(current: Screen, onSelect: (Screen) -> Unit) {
    val items = listOf(
        TabItem(Screen.Home, Icons.Filled.Home, R.string.nav_home),
        TabItem(Screen.Subscriptions, LiteIcons.Subscriptions, R.string.nav_subscriptions),
        TabItem(Screen.Library, LiteIcons.VideoLibrary, R.string.nav_library),
        TabItem(Screen.Search, Icons.Filled.Search, R.string.nav_search),
    )
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
    ) {
        items.forEach { item ->
            NavigationBarItem(
                selected = current == item.screen,
                onClick = { onSelect(item.screen) },
                icon = {
                    Icon(item.icon, contentDescription = stringResource(item.label))
                },
                label = {
                    Text(
                        text = stringResource(item.label),
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                alwaysShowLabel = true,
            )
        }
    }
}

private data class TabItem(
    val screen: Screen,
    val icon: ImageVector,
    @StringRes val label: Int,
)

@Composable
private fun titleFor(screen: Screen): String = when (screen) {
    Screen.Home -> stringResource(R.string.app_name)
    Screen.Subscriptions -> stringResource(R.string.nav_subscriptions)
    Screen.Library -> stringResource(R.string.nav_library)
    Screen.Search -> stringResource(R.string.nav_search)
    Screen.Settings -> stringResource(R.string.settings_title)
    is Screen.Player, Screen.Login -> ""
}

/** The feed a screen refreshes from the top bar, if any. */
private fun Screen.feedKey(): FeedKey? = when (this) {
    Screen.Home -> FeedKey.Home
    Screen.Subscriptions -> FeedKey.Subscriptions
    Screen.Library -> FeedKey.Library
    else -> null
}
