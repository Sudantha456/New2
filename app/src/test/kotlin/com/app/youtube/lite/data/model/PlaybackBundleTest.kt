package com.app.youtube.lite.data.model

import com.app.youtube.lite.core.util.AppJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bundle is the only channel between the UI process and the playback service: it is JSON-encoded
 * into the `MediaItem`'s request metadata and decoded inside the service after a Binder hop. If it
 * does not round-trip, playback silently has no ladder to fall back on — so the round trip is
 * tested rather than assumed.
 */
class PlaybackBundleTest {

    private fun spec(candidateId: String, height: Int, split: Boolean) = MediaSpec(
        candidateId = candidateId,
        label = "${height}p",
        videoUrl = "https://r1.googlevideo.com/videoplayback?itag=137",
        videoMime = "video/mp4",
        videoHeight = height,
        videoFps = 30,
        videoCodec = "avc1.640028",
        audioUrl = if (split) "https://r1.googlevideo.com/videoplayback?itag=140" else null,
        audioMime = if (split) "audio/mp4" else null,
        audioCodec = if (split) "mp4a.40.2" else null,
        audioBitrate = 128_000,
        score = 1_000 - height,
    )

    private val bundle = PlaybackBundle(
        videoId = "dQw4w9WgXcQ",
        title = "Never Gonna Give You Up",
        channel = "Rick Astley",
        channelId = "UCuAXFkgsw1L7xaCfnd5JJOw",
        thumbnailUrl = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hq720.jpg",
        durationMs = 213_000L,
        viewCount = 1_500_000_000L,
        candidates = listOf(
            spec("v137+a140", 1080, split = true),
            spec("v136+a140", 720, split = true),
            spec("muxed-18", 360, split = false),
        ),
        selectedIndex = 0,
    )

    @Test
    fun `current follows the selected index`() {
        assertEquals("v137+a140", bundle.current?.candidateId)
        assertEquals("v136+a140", bundle.withCandidate(1).current?.candidateId)
        assertEquals("muxed-18", bundle.withNextCandidate()?.withNextCandidate()?.current?.candidateId)
    }

    @Test
    fun `walking past the last rung reports exhaustion`() {
        val last = bundle.withCandidate(bundle.candidates.lastIndex)
        assertNull(last.withNextCandidate())
    }

    @Test
    fun `an out-of-range selection is ignored rather than crashing`() {
        assertEquals(bundle, bundle.withCandidate(-1))
        assertEquals(bundle, bundle.withCandidate(99))
    }

    @Test
    fun `split and muxed specs describe themselves correctly`() {
        assertTrue(bundle.candidates[0].isSplit)
        assertTrue(bundle.candidates[0].isPlayable)
        assertTrue(bundle.candidates[2].isMuxed)
        assertTrue(!bundle.candidates[2].isSplit)
    }

    @Test
    fun `the bundle survives the json round trip used by the media session`() {
        val encoded = AppJson.encodeToString(bundle)
        val decoded = AppJson.decodeFromString<PlaybackBundle>(encoded)
        assertEquals(bundle, decoded)
        // Defaults must survive too: a lost `androidUserAgent` flag means a 403 on the media request.
        assertEquals(true, decoded.candidates.first().androidUserAgent)
        assertNotNull(decoded.current)
    }

    @Test
    fun `related videos travel with the bundle so the service can autoplay`() {
        val withRelated = bundle.copy(
            related = listOf(
                VideoItem(id = "abcdefghijk", title = "Next up", channel = "Someone"),
            ),
        )
        val decoded = AppJson.decodeFromString<PlaybackBundle>(AppJson.encodeToString(withRelated))
        assertEquals(1, decoded.related.size)
        assertEquals("abcdefghijk", decoded.related.first().id)
        assertEquals("https://www.youtube.com/watch?v=abcdefghijk", decoded.related.first().watchUrl)
    }
}
