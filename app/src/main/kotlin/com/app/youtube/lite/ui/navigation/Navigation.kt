package com.app.youtube.lite.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable

/**
 * Every destination in the app.
 *
 * A sealed hierarchy rather than strings or a URI builder: the set of screens is small and fixed,
 * and making it a type means the navigation `when` is exhaustive — adding a screen without
 * handling it is a compile error instead of a blank page at runtime.
 */
sealed interface Screen {
    /** Tab roots. */
    data object Home : Screen
    data object Subscriptions : Screen
    data object Library : Screen
    data object Search : Screen

    /** Pushable destinations. */
    data class Player(val videoId: String) : Screen
    data object Settings : Screen
    data object Login : Screen

    /** Tabs are the screens that stay at the bottom of the stack while others are pushed. */
    val isTab: Boolean get() = this is Home || this is Subscriptions || this is Library || this is Search
}

/**
 * The back stack.
 *
 * Written by hand instead of pulling in `androidx.navigation`: the app has seven destinations, no
 * deep graph, no shared element transitions and no dynamic feature modules — everything
 * Navigation-Compose would provide is unused, while its dependency (and its runtime graph
 * resolution on every navigation) is not free. What is actually needed is a list of screens, a
 * push and a pop.
 *
 * The stack is a [androidx.compose.runtime.snapshotStateList], so pushes and pops recompose the
 * host exactly as `NavHost` would have. It is `@Stable` and saved across configuration changes by
 * [rememberNavStack].
 */
@Stable
class NavStack(initial: Screen = Screen.Home) {

    private val entries = mutableStateListOf(initial)

    val current: Screen get() = entries.last()

    /** The tab that should be highlighted in the bottom bar. */
    val activeTab: Screen get() = entries.firstOrNull { it.isTab } ?: entries.first()

    val canGoBack: Boolean get() = entries.size > 1

    /** Pushes a non-tab destination. Pushing the same screen twice in a row is a no-op. */
    fun push(screen: Screen) {
        if (screen == entries.last()) return
        entries.add(screen)
    }

    /** Returns false when the back press should leave the app instead. */
    fun pop(): Boolean {
        if (entries.size <= 1) return false
        entries.removeAt(entries.lastIndex)
        return true
    }

    /**
     * Switches tabs: the tapped tab becomes the new root and everything pushed on top of it is
     * dropped, which is what makes "go Home" from inside a video behave like a tab switch rather
     * than a growing stack.
     */
    fun selectTab(tab: Screen) {
        entries.clear()
        entries.add(tab)
    }

    /** Replaces the whole stack — used after sign-in to return to where the user was going. */
    fun resetTo(screen: Screen) {
        entries.clear()
        entries.add(screen)
    }

    /** Keys a video is open on, for the "already playing this video" check. */
    fun currentPlayingId(): String? = (entries.lastOrNull() as? Screen.Player)?.videoId
}

/** Remembers a [NavStack] across recompositions and configuration changes. */
@Composable
fun rememberNavStack(initial: Screen = Screen.Home): NavStack =
    rememberSaveable(saver = NavStackSaver) { NavStack(initial) }

/** Saves stack depth and the video ids of pushed players — enough to restore a plausible stack. */
private val NavStackSaver: Saver<NavStack, List<String>> = Saver(
    // Saving the tab root is enough: the pushed trail is at most a player or two, and restoring
    // the user straight back into whatever they were watching is what a launcher restart should do.
    save = { stack -> listOf(tabKey(stack.activeTab)) },
    restore = { saved ->
        NavStack(
            when (saved.firstOrNull()) {
                TAB_SUBSCRIPTIONS -> Screen.Subscriptions
                TAB_LIBRARY -> Screen.Library
                TAB_SEARCH -> Screen.Search
                else -> Screen.Home
            },
        )
    },
)

private const val TAB_HOME = "home"
private const val TAB_SUBSCRIPTIONS = "subscriptions"
private const val TAB_LIBRARY = "library"
private const val TAB_SEARCH = "search"

private fun tabKey(screen: Screen): String = when (screen) {
    Screen.Subscriptions -> TAB_SUBSCRIPTIONS
    Screen.Library -> TAB_LIBRARY
    Screen.Search -> TAB_SEARCH
    else -> TAB_HOME
}
