package com.app.youtube.lite.core.perf

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Build
import android.os.Debug
import coil.ImageLoader
import com.app.youtube.lite.core.net.Http
import com.app.youtube.lite.core.util.Formatters
import com.app.youtube.lite.core.util.L

/**
 * Everything the app does about memory *pressure*, in one place.
 *
 * The rules of the game on Android:
 *  • the platform tells you when memory is tight (`onTrimMemory`) and kills you if you ignore it;
 *  • the largest reclaimable block in a video app is almost always the image cache — thumbnails
 *    are decoded at 480 px wide and kept for smooth scrolling;
 *  • the second largest is the media buffer inside ExoPlayer, which is why it is capped at 20 MB
 *    by `LowRamLoadControl`, and the third is the HTTP connection pool's read/write buffers.
 *
 * So the trim ladder is: bitmaps first (they are cache — dropping them costs a re-download of a
 * few KB), then sockets (re-established in one handshake), never the player (dropping a media
 * buffer under a watching user *is* a rebuffer, which is the thing this app is built to avoid).
 */
object MemoryTrimmer {

    /**
     * Handles a platform trim callback. Returns `true` when the app was told to release
     * *non-essential* resources (the levels at which a well-behaved app must visibly shrink).
     */
    fun onTrimMemory(level: Int, imageLoader: ImageLoader?): Boolean {
        L.d("Memory") { "onTrimMemory(${levelName(level)})" }

        return when {
            // Background and the process may be killed at any moment: drop everything droppable.
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                clearImages(imageLoader)
                Http.trim()
                true
            }

            // Nothing is visible: the image cache is worthless until the user returns.
            level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                clearImages(imageLoader)
                true
            }

            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                clearImages(imageLoader)
                Http.trim()
                true
            }

            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                clearImages(imageLoader)
                true
            }

            // MODERATE / BACKGROUND / RUNNING_MODERATE: the platform is asking politely. We hold
            // no page cache of our own, so there is nothing honest to release here.
            else -> false
        }
    }

    /** Drops decoded thumbnails. Cheap and instant — Coil refetches them from the disk/HTTP cache. */
    fun clearImages(imageLoader: ImageLoader?) {
        val cache = imageLoader?.memoryCache ?: return
        val before = cache.size
        cache.clear()
        L.d("Memory") { "cleared ${Formatters.bytes(before.toLong())} of decoded bitmaps" }
    }

    /** `ActivityManager.isLowRamDevice`: the flag that decides buffer sizes and prefetch policy. */
    fun isLowRamDevice(context: Context): Boolean =
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true

    /** Per-app heap limits, so the debug overlay can say what is actually available. */
    fun heapSnapshot(context: Context): HeapSnapshot {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val javaHeapMb = manager?.memoryClass ?: 0
        val largeHeapMb = manager?.largeMemoryClass ?: 0
        return HeapSnapshot(
            javaHeapBytes = javaHeapMb.toLong() * 1024L * 1024L,
            largeHeapBytes = largeHeapMb.toLong() * 1024L * 1024L,
            usedBytes = usedHeapBytes(),
            nativeBytes = nativeHeapBytes(),
        )
    }

    /** ART's own view of the Java heap: cheap, and the number the LMK is actually reacting to. */
    fun usedHeapBytes(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    /** Native allocations (decoders, WebView, Skia) — invisible to the Java heap but not to the LMK. */
    fun nativeHeapBytes(): Long = runCatching { Debug.getNativeHeapAllocatedSize() }.getOrDefault(0L)

    /** CPU ABI awareness, used only for the diagnostics line in Settings. */
    fun primaryAbi(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) Build.SUPPORTED_ABIS.firstOrNull() ?: "?"
        else "?"

    private fun levelName(level: Int): String = when (level) {
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE"
        ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE"
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE"
        else -> level.toString()
    }

    /** Snapshot of the process's memory limits and current usage. */
    data class HeapSnapshot(
        val javaHeapBytes: Long,
        val largeHeapBytes: Long,
        val usedBytes: Long,
        val nativeBytes: Long,
    ) {
        val summary: String
            get() = "java ${Formatters.bytes(usedBytes)}/${Formatters.bytes(javaHeapBytes)} · " +
                "native ${Formatters.bytes(nativeBytes)}"
    }
}
