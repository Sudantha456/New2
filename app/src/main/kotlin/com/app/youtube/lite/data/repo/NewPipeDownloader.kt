package com.app.youtube.lite.data.repo

import com.app.youtube.lite.core.net.Http
import com.app.youtube.lite.core.net.UserAgents
import com.app.youtube.lite.core.util.L
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.io.IOException

/**
 * Bridges NewPipeExtractor's [Downloader] contract onto the app's single [OkHttpClient].
 *
 * Routing the extractor through the same client is what makes "logged-in extraction" work at
 * all: age-restricted, members-only and private videos need the user's cookies, and they are
 * already installed in the shared cookie jar. It also means HTTP/2 multiplexing, connection
 * reuse, gzip and the shared timeout policy apply to every extractor request for free.
 *
 * The exception mapping is contractual: the extractor uses [ReCaptchaException] to signal a
 * rate-limited/captcha-walled response, and expects `IOException` for transport failures so it
 * can fail the extraction instead of mis-parsing an error page.
 */
class NewPipeDownloader(
    private val http: OkHttpClient,
) : Downloader() {

    override fun execute(request: Request): Response {
        val method = request.httpMethod().uppercase()
        val bodyBytes = request.dataToSend()
        val mediaType = request.headers()
            ?.entries
            ?.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value
            ?.firstOrNull()
            ?.toMediaTypeOrNull()
            ?: DEFAULT_JSON

        val builder = okhttp3.Request.Builder().url(request.url())
        when (method) {
            "GET" -> builder.get()
            "HEAD" -> builder.head()
            "POST", "PUT", "PATCH", "DELETE" -> builder.method(
                method,
                bodyBytes?.toRequestBody(mediaType) ?: EMPTY_BODY,
            )

            else -> builder.method(method, null)
        }

        request.headers()?.forEach { (name, values) ->
            values.forEach { value -> builder.addHeader(name, value) }
        }
        if (builder.build().header("User-Agent") == null) {
            builder.header("User-Agent", UserAgents.DESKTOP)
        }

        val call = http.newCall(builder.build())
        return try {
            call.execute().use { response ->
                val payload = response.body?.string().orEmpty()
                val code = response.code

                if (code == 429 && payload.contains(RECAPTCHA_MARKER, ignoreCase = true)) {
                    throw ReCaptchaException(
                        "reCaptcha challenge requested for ${request.url()}",
                        request.url(),
                    )
                }

                Response(
                    code,
                    response.message,
                    response.headers.toMultimap(),
                    payload,
                    response.request.url.toString(),
                )
            }
        } catch (io: IOException) {
            L.w("Extractor") { "request failed: ${request.url()} (${io.message})" }
            throw io
        }
    }

    private companion object {
        val DEFAULT_JSON = "application/json; charset=utf-8".toMediaTypeOrNull()
        val EMPTY_BODY = ByteArray(0).toRequestBody(null)
        const val RECAPTCHA_MARKER = "recaptcha"
    }
}

/**
 * One-time initialisation of the extractor with our downloader, plus the locale/country that
 * decide which language the returned metadata is in.
 *
 * `NewPipe.init` is idempotent and cheap, but it must happen before the first extraction —
 * see [com.app.youtube.lite.LiteApp].
 */
object ExtractorBootstrap {

    @Volatile
    private var installed = false

    /** The downloader the extractor was installed with (kept for diagnostics). */
    @Volatile
    var downloader: NewPipeDownloader? = null
        private set

    val isInstalled: Boolean get() = installed

    fun install(
        http: OkHttpClient = Http.client,
        languageCode: String = "en",
        countryCode: String = "US",
    ) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val instance = NewPipeDownloader(http)
            NewPipe.init(
                instance,
                Localization(languageCode),
                ContentCountry(countryCode),
            )
            downloader = instance
            installed = true
            L.i("Extractor") { "NewPipeExtractor installed (hl=$languageCode, gl=$countryCode)" }
        }
    }
}
