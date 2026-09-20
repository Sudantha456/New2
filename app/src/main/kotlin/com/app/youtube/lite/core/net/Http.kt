package com.app.youtube.lite.core.net

import com.app.youtube.lite.core.util.L
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * User agents, in one place, because *which* UA is sent decides whether YouTube answers at all.
 *
 * The rule that matters: a stream URL is only valid for the client that produced it. URLs
 * extracted through YouTube's Android client (`c=ANDROID`) must be fetched with the Android app's
 * user agent; URLs from the web client must be fetched with a browser user agent. Sending the
 * wrong one produces a `403` that looks exactly like an expired URL, which is a genuinely
 * expensive bug to chase — hence [forMediaUrl].
 */
object UserAgents {

    /** Desktop Chrome: what the web client and the login WebView expect. */
    const val DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** Mobile Chrome: used for `m.youtube.com` HTML the extractor may fetch. */
    const val MOBILE_WEB =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    /**
     * The Android app's own user agent. The version tracks
     * `ClientsConstants.ANDROID_CLIENT_VERSION` of the pinned NewPipeExtractor build — if the two
     * drift apart, YouTube answers `c=ANDROID` media requests with `403`.
     */
    const val ANDROID_APP = "com.google.android.youtube/21.03.36 (Linux; U; Android 14; en_US) gzip"

    /** The iOS app's user agent, for `c=IOS` streams. */
    const val IOS_APP =
        "com.google.ios.youtube/21.03.2 (iPhone16,2; U; CPU iOS 18_7_2 like Mac OS X; en_US)"

    /** Picks the user agent that matches the client embedded in a `googlevideo` URL. */
    fun forMediaUrl(url: String?): String = when {
        url == null -> DESKTOP
        url.contains("c=ANDROID", ignoreCase = true) -> ANDROID_APP
        url.contains("c=IOS", ignoreCase = true) -> IOS_APP
        else -> DESKTOP
    }
}

/**
 * Hosts whose cookies are allowed anywhere near this app. Cookies are accepted from — and only
 * from — YouTube's own properties; everything else is dropped on the floor, so a redirect into an
 * unrelated domain can never influence an authenticated request.
 */
internal fun isYouTubeCookieHost(host: String): Boolean {
    val h = host.removePrefix(".")
    return h == "youtube.com" || h.endsWith(".youtube.com") ||
        h == "youtu.be" ||
        h == "google.com" || h.endsWith(".google.com") ||
        h == "googlevideo.com" || h.endsWith(".googlevideo.com") ||
        h == "ytimg.com" || h.endsWith(".ytimg.com")
}

/**
 * The cookie store for the whole app.
 *
 * In-memory only, on purpose: the six session cookies that actually matter are persisted by
 * `AuthManager` into `EncryptedSharedPreferences`, and *everything else* YouTube sets — visitor
 * ids, consent state, A/B experiment flags — is deliberately ephemeral. A cookie jar that writes
 * to disk is a cookie jar that can be read off a stolen device; this one holds at most a few
 * dozen entries and forgets them when the process dies.
 *
 * The jar is shared by the InnerTube client, the extractor and the media data sources, which is
 * why an age-restricted or members-only video plays immediately after login without any request
 * plumbing: the cookies are simply *there* for every caller.
 */
class LiteCookieJar : CookieJar {

    private val store = ConcurrentHashMap<String, Cookie>()

    /** Replaces the session cookies, leaving YouTube's own incidental cookies untouched. */
    fun install(cookies: List<Cookie>) {
        cookies.forEach { store[key(it)] = it }
        L.d("CookieJar") { "installed ${cookies.size} session cookies (${store.size} total)" }
    }

    /** Removes every cookie — used on logout so no trace of the session survives in memory. */
    fun clear() {
        val size = store.size
        store.clear()
        L.d("CookieJar") { "cleared $size cookies" }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val host = url.host
        if (!isYouTubeCookieHost(host)) return
        cookies.forEach { cookie ->
            // A cookie with an empty value is YouTube's way of deleting one; honour that.
            if (cookie.value.isEmpty()) store.remove(key(cookie)) else store[key(cookie)] = cookie
        }
        // Bounded: YouTube sets a handful of cookies per session, but a pathological server must
        // not be able to grow this map without limit.
        if (store.size > MAX_COOKIES) {
            val overflow = store.size - MAX_COOKIES
            store.keys.take(overflow).forEach(store::remove)
            L.w("CookieJar") { "cookie store over $MAX_COOKIES entries; evicted $overflow" }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (!isYouTubeCookieHost(url.host)) return emptyList()
        val now = System.currentTimeMillis()
        val result = ArrayList<Cookie>(8)
        store.values.forEach { cookie ->
            if (cookie.expiresAt > now && cookie.matches(url)) result.add(cookie)
        }
        return result
    }

    fun size(): Int = store.size

    private fun key(cookie: Cookie) = "${cookie.domain}|${cookie.path}|${cookie.name}"

    private companion object {
        const val MAX_COOKIES = 96
    }
}

/**
 * Cross-cutting request headers.
 *
 * `Origin`/`Referer` are why this interceptor exists: YouTube's web endpoints reject requests
 * that arrive without them, and the extractor only sets them on *some* of its requests. Adding
 * them centrally means every caller — our InnerTube client, the extractor's own Rhino JS fetch,
 * the media data sources — behaves like a browser without each of them remembering to.
 *
 * `Accept-Encoding` is deliberately absent: OkHttp adds `gzip` itself and transparently inflates
 * the response, and it correctly skips that for ranged media requests (where inflation would
 * corrupt the byte offsets).
 */
class DefaultHeadersInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        val builder = request.newBuilder()

        if (request.header("Accept-Language") == null) {
            builder.header("Accept-Language", ACCEPT_LANGUAGE)
        }
        if (request.header("User-Agent") == null) {
            builder.header("User-Agent", UserAgents.DESKTOP)
        }

        if (host == "youtube.com" || host.endsWith(".youtube.com")) {
            if (request.header("Origin") == null) {
                builder.header("Origin", YOUTUBE_ORIGIN)
            }
            if (request.header("Referer") == null) {
                builder.header("Referer", "$YOUTUBE_ORIGIN/")
            }
        }

        return chain.proceed(builder.build())
    }

    private companion object {
        const val YOUTUBE_ORIGIN = "https://www.youtube.com"
        const val ACCEPT_LANGUAGE = "en-US,en;q=0.9"
    }
}

/**
 * The single HTTP client for the entire app.
 *
 * Why one instance: every `OkHttpClient` owns its own connection pool, dispatcher and thread
 * pool. A second client is a second set of idle sockets and a second set of threads — pure
 * overhead for an app whose whole point is staying light. One client also means one HTTP/2
 * connection to youtube.com, shared by the feeds, the extractor and (via `OkHttpDataSource`) the
 * media streams, so a playing video does not have to renegotiate TLS to load the next page of a
 * feed.
 *
 * Sizing decisions:
 *  • HTTP/2 by preference (h2 multiplexes the many small InnerTube calls over one connection),
 *    falling back to HTTP/1.1;
 *  • 5 idle connections kept for 5 minutes — keep-alive survives a user switching apps;
 *  • 64 concurrent requests, 16 per host: enough for a fast scroll of thumbnails;
 *  • no `callTimeout`. Media range requests are legitimately long-lived; killing a slow range read
 *    would produce a stall that looks like a network fault. Connect/read/write timeouts still
 *    bound a dead socket;
 *  • no disk cache: everything here is either signed (media URLs, which expire) or stale within
 *    minutes (feeds). A cache would burn storage and battery for no hit rate.
 */
object Http {

    private const val MAX_IDLE_CONNECTIONS = 5
    private const val KEEP_ALIVE_MINUTES = 5L
    private const val CONNECT_TIMEOUT_SECONDS = 15L
    private const val READ_TIMEOUT_SECONDS = 20L
    private const val WRITE_TIMEOUT_SECONDS = 20L
    private const val MAX_REQUESTS = 64
    private const val MAX_REQUESTS_PER_HOST = 16

    val cookieJar = LiteCookieJar()

    val client: OkHttpClient by lazy {
        L.i("Http") { "building singleton OkHttpClient (HTTP/2, pooled)" }
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .addInterceptor(DefaultHeadersInterceptor())
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectionPool(okhttp3.ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE_MINUTES, TimeUnit.MINUTES))
            .dispatcher(
                Dispatcher().apply {
                    maxRequests = MAX_REQUESTS
                    maxRequestsPerHost = MAX_REQUESTS_PER_HOST
                },
            )
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * Drops the idle sockets. Called when the app goes to the background: five keep-alive
     * connections and their read/write buffers are pure waste in a process Android is actively
     * trying to make smaller, and re-establishing one later costs a single handshake.
     *
     * Note what is *not* done here: the dispatcher's executor is never shut down. This client is
     * a process-wide singleton — closing its executor would leave every later request failing
     * with a `RejectedExecutionException`, i.e. a permanently broken app after one background
     * transition.
     */
    fun trim() {
        client.connectionPool.evictAll()
        L.d("Http") { "connection pool evicted" }
    }
}
