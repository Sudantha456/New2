package com.app.youtube.lite.player

import android.content.Context
import android.content.SharedPreferences
import com.app.youtube.lite.core.util.L

/**
 * "Resume where you left off" positions, kept in a small bounded store.
 *
 * Plain [SharedPreferences] on purpose: the values are non-sensitive (a video id and a
 * millisecond offset), the volume is tiny, and the read happens while a video is being
 * prepared — a synchronous, in-memory map lookup beats suspending on DataStore or decrypting
 * an encrypted blob in that moment. Writes are `apply()`-batched by the platform, and the
 * store is trimmed to [MAX_ENTRIES] so it can never grow without bound.
 */
class PlaybackPositions(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Positions at or below this are treated as "start from the beginning". */
    private val minResumeMs = 15_000L

    fun get(videoId: String): Long = prefs.getLong(videoId, 0L)

    fun put(videoId: String, positionMs: Long) {
        if (positionMs <= minResumeMs) {
            prefs.edit().remove(videoId).apply()
            return
        }
        prefs.edit().putLong(videoId, positionMs).apply()
        trim()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun trim() {
        val all = prefs.all
        if (all.size <= MAX_ENTRIES) return
        val editor = prefs.edit()
        // Values are positions; the smallest ones are the least valuable resumption points.
        all.entries
            .asSequence()
            .filter { it.value is Long }
            .sortedBy { it.value as Long }
            .take(all.size - MAX_ENTRIES)
            .forEach { editor.remove(it.key) }
        editor.apply()
        L.d("Positions") { "trimmed resume-position store to $MAX_ENTRIES entries" }
    }

    private companion object {
        const val FILE = "youtube_lite_positions"
        const val MAX_ENTRIES = 120
    }
}
