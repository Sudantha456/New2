package com.app.youtube.lite.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.app.youtube.lite.core.di.AppGraph
import com.app.youtube.lite.core.net.Http
import com.app.youtube.lite.core.perf.MemoryTrimmer
import com.app.youtube.lite.core.util.L

/**
 * The app's only activity.
 *
 * One activity, and it stays that way: a player screen, a settings screen and a login screen do
 * not need three activities, three lifecycles and three sets of intent filters. Compose swaps the
 * content, the back stack is a list (see `Navigation.kt`), and `launchMode="singleTask"` means a
 * shared link lands in the running instance instead of starting a second one next to it.
 *
 * The activity also declares `configChanges` for orientation/screen size in the manifest, which is
 * what makes fullscreen playback free: rotating for a video re-lays-out the composition instead of
 * destroying the activity, releasing the player and re-preparing the media source.
 */
class MainActivity : ComponentActivity() {

    /** Set from the launch intent (deep link), and updated when a new one arrives. */
    private var deepLinkVideoId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before setContent: edge-to-edge has to be requested before the first window layout, or
        // the app draws one frame with the system bars opaque.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        deepLinkVideoId = videoIdFrom(intent)

        setContent {
            LiteAppShell(
                graph = AppGraph.get(),
                deepLinkVideoId = deepLinkVideoId,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinkVideoId = videoIdFrom(intent)
    }

    override fun onStop() {
        super.onStop()
        // The image cache is the largest reclaimable block we own, and nothing on screen needs it
        // while the app is not visible. Coil refetches from its disk cache in a few milliseconds.
        if (!isChangingConfigurations) {
            MemoryTrimmer.clearImages(coil.Coil.imageLoader(this))
            Http.trim()
            L.d("MainActivity") { "backgrounded: image cache cleared, sockets evicted" }
        }
    }

    /** Extracts an 11-character video id from any of YouTube's URL shapes. */
    private fun videoIdFrom(intent: Intent?): String? {
        val data: Uri = intent?.data ?: return null
        val host = data.host?.lowercase() ?: return null
        val candidate = when {
            host == "youtu.be" -> data.lastPathSegment
            host.endsWith("youtube.com") -> {
                val path = data.path.orEmpty()
                when {
                    path.startsWith("/watch") -> data.getQueryParameter("v")
                    path.startsWith("/shorts/") -> data.lastPathSegment
                    path.startsWith("/live/") -> data.lastPathSegment
                    path.startsWith("/embed/") -> data.lastPathSegment
                    else -> null
                }
            }
            else -> null
        }
        return candidate?.takeIf { it.length == YOUTUBE_ID_LENGTH }
    }

    private companion object {
        const val YOUTUBE_ID_LENGTH = 11
    }
}
