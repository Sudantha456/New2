package com.app.youtube.lite.data.innertube

/**
 * InnerTube constants.
 *
 * Values mirror NewPipeExtractor's `ClientsConstants` / `YoutubeParsingHelper` for the
 * `WEB` client, which is the dialect this app speaks. The client *version* is validated at
 * runtime and refreshed from `sw.js` when it goes stale (see [InnerTubeClient]), so a
 * YouTube-side bump degrades to one extra request instead of a broken feed.
 */
object Endpoints {

    const val BASE_URL = "https://www.youtube.com/youtubei/v1/"
    const val WWW = "https://www.youtube.com"
    const val ORIGIN = "https://www.youtube.com"

    /** `X-YouTube-Client-Name` for the WEB client. */
    const val WEB_CLIENT_ID = "1"
    const val WEB_CLIENT_NAME = "WEB"

    /** Known-good WEB client version — validated against `guide` before first use. */
    const val WEB_CLIENT_VERSION = "2.20260120.01.00"

    const val PRETTY_PRINT_OFF = "prettyPrint=false"

    // Endpoints
    const val BROWSE = "browse"
    const val NEXT = "next"
    const val SEARCH = "search"
    const val GUIDE = "guide"
    const val VISITOR_ID = "visitor_id"
    const val PLAYER = "player"

    /** `guide` returns ~30 KB when the client version is accepted; this is the sanity floor. */
    const val GUIDE_VALID_RESPONSE_BYTES = 5_000

    /** `sw.js` carries the current client version when the hardcoded one is rejected. */
    const val SW_JS = "https://www.youtube.com/sw.js"

    const val ACCEPT_LANGUAGE = "en-US,en;q=0.9"

    /** Consent cookie pre-answer, so no request ever bounces through the consent wall. */
    const val CONSENT_COOKIE = "SOCS=CAISAiAD"
}
