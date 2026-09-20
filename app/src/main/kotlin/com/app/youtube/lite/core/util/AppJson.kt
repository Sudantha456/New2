package com.app.youtube.lite.core.util

import kotlinx.serialization.json.Json

/**
 * The one JSON configuration used everywhere.
 *
 * `ignoreUnknownKeys` matters for the session blob (a future version may add fields) and
 * `encodeDefaults` keeps the [com.app.youtube.lite.data.model.PlaybackBundle] that travels
 * through the Binder self-describing. `Json` instances are immutable and thread-safe, so a
 * single shared instance avoids re-instantiating the schema caches on every call.
 */
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
}
