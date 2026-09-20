package com.app.youtube.lite.data.innertube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser is the app's exposure to YouTube's various renderer shapes, and those shapes change
 * without notice. These tests pin the *contract* — which renderer keys produce an item, what a
 * missing field does, how a Short is skipped, where the continuation token lives — so that a
 * refactor cannot quietly start dropping rows.
 */
class RendererParserTest {

    private fun page(body: String): JsonObject =
        Json.parseToJsonElement(body) as JsonObject

    private val videoRenderer = """
        {
          "videoId": "dQw4w9WgXcQ",
          "title": { "runs": [ { "text": "Never Gonna Give You Up" } ] },
          "ownerText": {
            "runs": [
              {
                "text": "Rick Astley",
                "navigationEndpoint": {
                  "browseEndpoint": { "browseId": "UCuAXFkgsw1L7xaCfnd5JJOw" }
                }
              }
            ]
          },
          "lengthText": { "simpleText": "3:33" },
          "shortViewCountText": { "simpleText": "1.5B views" },
          "publishedTimeText": { "simpleText": "16 years ago" },
          "thumbnail": {
            "thumbnails": [
              { "url": "https://i.ytimg.com/vi/dQw4w9WgXcQ/default.jpg", "width": 120, "height": 90 },
              { "url": "https://i.ytimg.com/vi/dQw4w9WgXcQ/hq720.jpg", "width": 720, "height": 404 }
            ]
          }
        }
    """

    @Test
    fun `extracts a standard video renderer`() {
        val items = parseItems(
            page(
                """
                { "contents": { "richGridRenderer": { "contents": [
                  { "richItemRenderer": { "content": { "videoRenderer": $videoRenderer } } }
                ] } } }
                """,
            ),
        )

        assertEquals(1, items.size)
        val item = items.first()
        assertEquals("dQw4w9WgXcQ", item.id)
        assertEquals("Never Gonna Give You Up", item.title)
        assertEquals("Rick Astley", item.channel)
        assertEquals("UCuAXFkgsw1L7xaCfnd5JJOw", item.channelId)
        assertEquals(213L, item.durationSeconds)
        assertEquals(1_500_000_000L, item.viewCount)
        assertEquals("16 years ago", item.publishedText)
        // The 720 px candidate is chosen over the 120 px one.
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hq720.jpg", item.thumbnailUrl)
        assertFalse(item.isLive)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", item.watchUrl)
    }

    @Test
    fun `skips shorts and malformed ids`() {
        val items = parseItems(
            page(
                """
                { "contents": { "richGridRenderer": { "contents": [
                  { "richItemRenderer": { "content": { "reelItemRenderer": {
                      "videoId": "abcdefghijk",
                      "headline": { "simpleText": "A Short" }
                  } } } },
                  { "richItemRenderer": { "content": { "videoRenderer": {
                      "videoId": "tooShort",
                      "title": { "simpleText": "Bad id" }
                  } } } },
                  { "richItemRenderer": { "content": { "videoRenderer": {
                      "videoId": "12345678901"
                  } } } },
                  { "richItemRenderer": { "content": { "videoRenderer": $videoRenderer } } }
                ] } } }
                """,
            ),
        )

        // The Short, the wrong-length id and the title-less renderer are all dropped.
        assertEquals(1, items.size)
        assertEquals("dQw4w9WgXcQ", items.first().id)
    }

    @Test
    fun `detects a live stream from the thumbnail overlay`() {
        val items = parseItems(
            page(
                """
                { "contents": { "richGridRenderer": { "contents": [
                  { "richItemRenderer": { "content": { "videoRenderer": {
                      "videoId": "abcdefghijk",
                      "title": { "simpleText": "Live now" },
                      "thumbnailOverlays": [
                        { "thumbnailOverlayTimeStatusRenderer": { "style": "LIVE" } }
                      ]
                  } } } }
                ] } } }
                """,
            ),
        )

        assertEquals(1, items.size)
        assertTrue(items.first().isLive)
        // A live stream has no duration to display.
        assertEquals(0L, items.first().durationSeconds)
    }

    @Test
    fun `reads the continuation token from the continuation item`() {
        val page = page(
            """
            { "contents": { "richGridRenderer": { "contents": [
              { "richItemRenderer": { "content": { "videoRenderer": $videoRenderer } } },
              { "continuationItemRenderer": { "continuationEndpoint": {
                  "continuationCommand": { "token": "NEXT_PAGE_TOKEN" } } } }
            ] } } }
            """,
        )
        assertEquals("NEXT_PAGE_TOKEN", parseContinuation(page))
    }

    @Test
    fun `no continuation item means the feed is exhausted`() {
        assertNull(parseContinuation(page("""{ "contents": {} }""")))
    }

    @Test
    fun `an unexpected payload yields nothing rather than throwing`() {
        assertEquals(0, parseItems(page("""{ "contents": { "somethingNew": {} } }""")).size)
        assertEquals(0, parseItems(page("{}")).size)
    }

    @Test
    fun `falls back to a deterministic thumbnail when none are offered`() {
        val items = parseItems(
            page(
                """
                { "contents": { "richGridRenderer": { "contents": [
                  { "richItemRenderer": { "content": { "videoRenderer": {
                      "videoId": "abcdefghijk",
                      "title": { "simpleText": "No thumbnails" }
                  } } } }
                ] } } }
                """,
            ),
        )
        assertEquals("https://i.ytimg.com/vi/abcdefghijk/mqdefault.jpg", items.first().thumbnailUrl)
    }
}
