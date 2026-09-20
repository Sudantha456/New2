package com.app.youtube.lite.data.innertube

import com.app.youtube.lite.core.util.Formatters
import com.app.youtube.lite.data.model.VideoItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Turns InnerTube renderer trees into [VideoItem]s.
 *
 * Why walk the tree instead of modelling it:
 *  • a feed page is a `richGridRenderer` containing `richItemRenderer`s that each wrap one of
 *    five different video renderer shapes (`videoRenderer`, `compactVideoRenderer`,
 *    `gridVideoRenderer`, …) plus shelves, promo slots and continuation markers;
 *  • YouTube renames/moves those wrappers regularly, and a *structural* walk keeps working
 *    when it does. Typed DTOs would need a release for every experiment YouTube ships.
 *
 * The walk is depth-first, single pass, and stops at [MAX_ITEMS] — parsing a 500 KB response
 * costs well under a millisecond of IO-thread time and allocates only the items we keep.
 */
internal object RendererParser {

    /** Renderer keys that carry a playable video. `reelItemRenderer` (Shorts) is skipped. */
    private val VIDEO_RENDERER_KEYS = setOf(
        "videoRenderer",
        "compactVideoRenderer",
        "gridVideoRenderer",
        "playlistVideoRenderer",
        "playlistPanelVideoRenderer",
    )

    private const val MAX_ITEMS = 120
    private const val VIDEO_ID_LENGTH = 11

    /** Preferred thumbnail width: big enough to look sharp, small enough to stay in cache. */
    private const val THUMBNAIL_TARGET_WIDTH = 480

    // ── Feed & search ────────────────────────────────────────────────────────

    /** All video entries in document order, de-duplicated by video id. */
    fun parseItems(root: JsonElement, max: Int = MAX_ITEMS): List<VideoItem> {
        val seen = HashSet<String>(max * 2)
        val items = ArrayList<VideoItem>(max)
        root.walk { key, value ->
            if (items.size >= max) return@walk
            if (key !in VIDEO_RENDERER_KEYS) return@walk
            if (!seen.add(idOf(value) ?: return@walk)) return@walk
            rendererToItem(value)?.let(items::add)
        }
        return items
    }

    /**
     * Related videos for a watch page.
     *
     * Scoped deliberately to the `secondaryResults` subtree: a watch page also contains the
     * autoplay ("up next") panel and the comments' continuation, and neither belongs in a
     * user-facing "Related" list.
     */
    fun parseRelated(root: JsonElement, max: Int = 40): List<VideoItem> {
        var results: JsonElement? = null
        root.walk { key, value ->
            if (results == null && key == "secondaryResultsRenderer") results = value
        }
        val scope = results ?: root
        return parseItems(scope, max)
    }

    /**
     * Page continuation token.
     *
     * The *last* `continuationItemRenderer` on a browse/search page is the page-level one
     * (shelf-level continuations come earlier in document order and are ignored on purpose).
     */
    fun parseContinuation(root: JsonElement): String? {
        var token: String? = null
        root.walk { key, value ->
            if (key != "continuationItemRenderer") return@walk
            val candidate = value
                .obj("continuationEndpoint")
                ?.obj("continuationCommand")
                ?.str("token")
            if (!candidate.isNullOrEmpty()) token = candidate
        }
        return token
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private fun idOf(renderer: JsonObject): String? =
        renderer.str("videoId")?.takeIf { it.length == VIDEO_ID_LENGTH }

    private fun rendererToItem(renderer: JsonObject): VideoItem? {
        val id = idOf(renderer) ?: return null
        val title = renderer.text("title")?.takeIf { it.isNotBlank() } ?: return null
        val live = isLive(renderer)
        val durationText = renderer.text("lengthText")

        return VideoItem(
            id = id,
            title = title,
            channel = byline(renderer),
            channelId = channelId(renderer),
            thumbnailUrl = bestThumbnail(renderer) ?: fallbackThumbnail(id),
            durationSeconds = if (live) 0L else Formatters.parseDuration(durationText),
            viewCount = Formatters.parseCount(
                renderer.text("viewCountText") ?: renderer.text("shortViewCountText"),
            ),
            publishedText = renderer.text("publishedTimeText"),
            isLive = live,
        )
    }

    private fun byline(renderer: JsonObject): String =
        renderer.text("ownerText")
            ?: renderer.text("longBylineText")
            ?: renderer.text("shortBylineText")
            ?: ""

    private fun channelId(renderer: JsonObject): String? {
        val byline = renderer.obj("ownerText")
            ?: renderer.obj("longBylineText")
            ?: renderer.obj("shortBylineText")
            ?: return null
        return byline.arr("runs")
            ?.firstOrNull()
            ?.asObject()
            ?.obj("navigationEndpoint")
            ?.obj("browseEndpoint")
            ?.str("browseId")
    }

    private fun isLive(renderer: JsonObject): Boolean {
        val badges = renderer.arr("badges") ?: renderer.arr("ownerBadges")
        if (badges.hasLiveBadge()) return true

        // The time-status overlay is the most reliable signal on grid/compact renderers.
        val overlays = renderer.arr("thumbnailOverlays") ?: return false
        for (overlay in overlays) {
            val status = overlay.asObject()
                ?.obj("thumbnailOverlayTimeStatusRenderer") ?: continue
            if (status.str("style")?.uppercase() == "LIVE") return true
        }
        // Some shapes only carry the boolean.
        return renderer.bool("isLiveNow") == true
    }

    private fun JsonArray?.hasLiveBadge(): Boolean {
        if (this == null) return false
        for (badge in this) {
            val style = badge.asObject()
                ?.obj("metadataBadgeRenderer")
                ?.str("style") ?: continue
            if (style == "BADGE_STYLE_TYPE_LIVE_NOW") return true
        }
        return false
    }

    /**
     * Picks the thumbnail closest to [THUMBNAIL_TARGET_WIDTH].
     *
     * This is a real memory decision, not a cosmetic one: a 1280×720 ARGB bitmap is 3.5 MB and
     * with a hardware bitmap it occupies GPU memory, while 480×270 is 0.5 MB. At 120 fps the
     * display pipeline is bandwidth-bound — feeding it oversized textures costs power and
     * causes cache thrash during fast flings.
     */
    private fun bestThumbnail(renderer: JsonObject): String? {
        val thumbnails = renderer.obj("thumbnail")?.arr("thumbnails") ?: return null
        var bestUrl: String? = null
        var bestScore = Int.MAX_VALUE
        for (element in thumbnails) {
            val entry = element as? JsonObject ?: continue
            val url = entry.str("url") ?: continue
            val width = (entry["width"] as? JsonPrimitive)?.intOrNull ?: continue
            val score = if (width >= THUMBNAIL_TARGET_WIDTH) {
                width - THUMBNAIL_TARGET_WIDTH
            } else {
                (THUMBNAIL_TARGET_WIDTH - width) * 2 // penalise upscaling
            }
            if (score < bestScore) {
                bestScore = score
                bestUrl = url
            }
        }
        return bestUrl
    }

    /** Deterministic fallback: `i.ytimg.com` paths are stable per video id. */
    private fun fallbackThumbnail(videoId: String): String =
        "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"
}

/**
 * Top-level entry points.
 *
 * The parser itself is an `internal object` so that it can be tested as a unit and so that its
 * helpers stay private, but callers (the feed repository, search, the watch-next screen) only ever
 * need these three functions. Exposing them at package level keeps the call sites reading as plain
 * data transformations: `parseItems(page)`, `parseContinuation(page)`.
 */
fun parseItems(root: JsonElement, max: Int = DEFAULT_MAX_ITEMS): List<VideoItem> =
    RendererParser.parseItems(root, max)

/** Related videos for a watch page, scoped to the secondary results column. */
fun parseRelated(root: JsonElement, max: Int = DEFAULT_MAX_RELATED): List<VideoItem> =
    RendererParser.parseRelated(root, max)

/** The continuation cursor for the next page, or `null` when the feed is exhausted. */
fun parseContinuation(root: JsonElement): String? = RendererParser.parseContinuation(root)

/** Matches [RendererParser]'s internal cap; a feed page never carries more useful rows. */
private const val DEFAULT_MAX_ITEMS = 120
private const val DEFAULT_MAX_RELATED = 40
