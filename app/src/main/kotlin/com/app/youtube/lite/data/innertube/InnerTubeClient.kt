package com.app.youtube.lite.data.innertube

import com.app.youtube.lite.auth.AuthManager
import com.app.youtube.lite.core.util.L
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Raised for anything that prevents an InnerTube response from being used. */
class InnerTubeException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Client for YouTube's private InnerTube API — no backend, no API key, no SDK.
 *
 * The request shape is exactly what the website itself sends (`youtubei/v1/<endpoint>`,
 * `WEB` client context, `X-YouTube-Client-*` headers), plus a `SAPISIDHASH` `Authorization`
 * header when the user is signed in.
 *
 * Three behaviours matter for robustness:
 *  1. **Self-validating client version.** The hardcoded `WEB_CLIENT_VERSION` is proven against
 *     the lightweight `guide` endpoint before first use; when YouTube rejects it, the live
 *     version is scraped from `sw.js` (the same two-step NewPipeExtractor performs). A
 *     YouTube-side rollout therefore costs one extra request, not a broken app.
 *  2. **Visitor identity.** `visitorData` is fetched once per session and echoed in the client
 *     context and `X-Goog-Visitor-Id`; without it the home feed is delivered anonymously and
 *     noticeably thinner.
 *  3. **Bounded retries with jitter.** Idempotent POSTs are retried up to 3 times on transport
 *     failures and 5xx/429 — never on 4xx, which would just burn the user's data.
 */
class InnerTubeClient(
    private val http: OkHttpClient,
    private val auth: AuthManager,
    private val json: Json,
) {

    @Volatile
    private var clientVersion: String = Endpoints.WEB_CLIENT_VERSION

    @Volatile
    private var visitorData: String? = null

    @Volatile
    private var bootstrapped: Boolean = false

    private val bootstrapGate = Mutex()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // ── Public surface ───────────────────────────────────────────────────────

    /** `browse` — feeds, playlists, library, history, liked videos. */
    suspend fun browse(
        browseId: String,
        params: String? = null,
        continuation: String? = null,
    ): JsonObject {
        val body = buildJsonObject {
            put("context", context())
            put("browseId", browseId)
            if (params != null) put("params", params)
            if (continuation != null) put("continuation", continuation)
        }
        return post(Endpoints.BROWSE, body)
    }

    /** `next` — watch-next metadata and the related-videos column. */
    suspend fun next(videoId: String, playlistId: String? = null): JsonObject {
        val body = buildJsonObject {
            put("context", context())
            put("videoId", videoId)
            if (playlistId != null) put("playlistId", playlistId)
        }
        return post(Endpoints.NEXT, body)
    }

    /** `search` — query or continuation page. */
    suspend fun search(query: String, continuation: String? = null): JsonObject {
        val body = buildJsonObject {
            put("context", context())
            if (continuation != null) {
                put("continuation", continuation)
            } else {
                put("query", query)
            }
        }
        return post(Endpoints.SEARCH, body)
    }

    /** Exposed for the diagnostics screen. */
    fun currentClientVersion(): String = clientVersion

    fun currentVisitorData(): String? = visitorData

    /** Forces a re-bootstrap, e.g. after a login state change. */
    suspend fun invalidate() {
        bootstrapGate.withLock { bootstrapped = false }
    }

    // ── Transport ────────────────────────────────────────────────────────────

    private suspend fun post(endpoint: String, body: JsonObject): JsonObject {
        ensureBootstrapped()

        val url = Endpoints.BASE_URL + endpoint + "?" + Endpoints.PRETTY_PRINT_OFF
        var lastError: Throwable? = null

        for (attempt in 1..MAX_ATTEMPTS) {
            val request = buildRequest(url, body)
            runCatching {
                withContext(Dispatchers.IO) {
                    http.newCall(request).execute().use { response ->
                        val payload = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                            throw InnerTubeException(
                                "InnerTube $endpoint failed: HTTP ${response.code} " +
                                    "(${payload.take(180).replace('\n', ' ')})",
                            )
                        }
                        if (payload.isEmpty()) {
                            throw InnerTubeException("InnerTube $endpoint returned an empty body")
                        }
                        json.parseToJsonElement(payload) as? JsonObject
                            ?: throw InnerTubeException("InnerTube $endpoint returned non-object JSON")
                    }
                }
            }.onSuccess { return it }
                .onFailure { error ->
                    lastError = error
                    val retryable = isRetryable(error)
                    L.w("InnerTube") { "attempt $attempt/$MAX_ATTEMPTS for $endpoint failed: $error" }
                    if (!retryable || attempt == MAX_ATTEMPTS) throw error
                }

            // Exponential-ish backoff with jitter: keeps retries polite under rate limiting.
            delay(BACKOFF_BASE_MS * attempt + (0..BACKOFF_JITTER_MS).random().toLong())
        }
        throw InnerTubeException("InnerTube $endpoint exhausted retries", lastError)
    }

    private fun buildRequest(url: String, body: JsonObject): Request {
        val builder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(jsonMediaType))
            .header("Content-Type", "application/json")
            .header("Accept-Language", Endpoints.ACCEPT_LANGUAGE)
            .header("X-YouTube-Client-Name", Endpoints.WEB_CLIENT_ID)
            .header("X-YouTube-Client-Version", clientVersion)
            .header("X-Origin", Endpoints.ORIGIN)
            .header("Origin", Endpoints.ORIGIN)
            .header("Referer", Endpoints.ORIGIN + "/")

        visitorData?.let { builder.header("X-Goog-Visitor-Id", it) }

        // Authenticated request: sign with SAPISIDHASH. The cookie header is set explicitly so
        // it cannot be dropped by a cookie-jar miss after a logout/login cycle.
        auth.authorizationHeader()?.let { authorization ->
            builder.header("Authorization", authorization)
            builder.header("X-Goog-AuthUser", "0")
            auth.cookieHeader()?.let { builder.header("Cookie", it) }
        }
        return builder.build()
    }

    private fun context(): JsonObject = buildJsonObject {
        putJsonObject("client") {
            put("hl", "en")
            put("gl", "US")
            put("clientName", Endpoints.WEB_CLIENT_NAME)
            put("clientVersion", clientVersion)
            put("platform", "DESKTOP")
            put("utcOffsetMinutes", 0)
            visitorData?.let { put("visitorData", it) }
        }
        putJsonObject("user") {
            put("lockedSafetyMode", false)
        }
        putJsonObject("request") {
            put("useSsl", true)
            putJsonArray("internalExperimentFlags") { }
        }
    }

    private suspend fun ensureBootstrapped() {
        if (bootstrapped) return
        bootstrapGate.withLock {
            if (bootstrapped) return@withLock

            visitorData = auth.visitorData() ?: fetchVisitorData()
            if (!isClientVersionValid()) {
                L.w("InnerTube") { "hardcoded client version rejected — refreshing from sw.js" }
                extractClientVersionFromSwJs()?.let { clientVersion = it }
            }
            L.i("InnerTube") {
                "ready: client=$clientVersion visitor=${visitorData?.length ?: 0} bytes"
            }
            bootstrapped = true
        }
    }

    /**
     * `visitor_id` is the cheapest endpoint that returns `responseContext.visitorData`.
     * Failure is non-fatal: an anonymous session still works.
     */
    private suspend fun fetchVisitorData(): String? = runCatching {
        val body = buildJsonObject { put("context", context()) }
        val request = buildRequest(
            Endpoints.BASE_URL + Endpoints.VISITOR_ID + "?" + Endpoints.PRETTY_PRINT_OFF,
            body,
        )
        withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val payload = response.body?.string() ?: return@use null
                val root = json.parseToJsonElement(payload) as? JsonObject ?: return@use null
                root.obj("responseContext")?.str("visitorData")
            }
        }
    }.getOrElse {
        L.w("InnerTube") { "visitor_id unavailable: $it" }
        null
    }?.also { auth.updateVisitorData(it) }

    /**
     * Sends a tiny `guide` request whose response body size is a reliable liveness signal:
     * an accepted client version returns the full ~30 KB guide, a stale one returns a stub.
     */
    private suspend fun isClientVersionValid(): Boolean = runCatching {
        val body = buildJsonObject {
            put("context", context())
            put("fetchLiveState", true)
        }
        val request = buildRequest(
            Endpoints.BASE_URL + Endpoints.GUIDE + "?" + Endpoints.PRETTY_PRINT_OFF,
            body,
        )
        withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                response.isSuccessful && payload.length > Endpoints.GUIDE_VALID_RESPONSE_BYTES
            }
        }
    }.getOrElse {
        // Network trouble must not be mistaken for a rejected version.
        L.w("InnerTube") { "guide probe inconclusive: $it" }
        true
    }

    /** Scrapes `INNERTUBE_CONTEXT_CLIENT_VERSION` out of the service-worker bundle. */
    private suspend fun extractClientVersionFromSwJs(): String? = runCatching {
        val request = Request.Builder()
            .url(Endpoints.SW_JS)
            .header("Origin", Endpoints.ORIGIN)
            .header("Referer", Endpoints.ORIGIN + "/")
            .build()
        withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                VERSION_REGEX.find(payload)?.groupValues?.getOrNull(1)
            }
        }
    }.getOrElse {
        L.w("InnerTube") { "sw.js probe failed: $it" }
        null
    }

    private fun isRetryable(error: Throwable): Boolean {
        if (error is InnerTubeException) {
            val match = HTTP_STATUS_REGEX.find(error.message.orEmpty()) ?: return false
            val status = match.groupValues[1].toIntOrNull() ?: return false
            return status == 429 || status >= 500
        }
        return true // transport-level failure (IO, timeout, TLS) — worth one more try
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val BACKOFF_BASE_MS = 250L
        const val BACKOFF_JITTER_MS = 200

        val VERSION_REGEX =
            Regex("INNERTUBE_CONTEXT_CLIENT_VERSION\\\"\\s*:\\s*\\\"([0-9.]+?)\\\"")

        val HTTP_STATUS_REGEX = Regex("HTTP (\\d{3})")
    }
}
