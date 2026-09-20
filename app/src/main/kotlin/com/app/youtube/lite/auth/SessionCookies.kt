package com.app.youtube.lite.auth

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import okhttp3.Cookie

/**
 * The six session cookies that together authenticate InnerTube on behalf of the user, plus
 * the visitor identifier YouTube hands out for the (possibly signed-out) session.
 *
 * Only these are persisted — never the full cookie store. That keeps the encrypted blob
 * small and makes "log out" a well-defined, verifiable operation.
 */
@Immutable
@Serializable
data class SessionCookies(
    val sid: String,
    val hsid: String,
    val ssid: String,
    val apisid: String,
    val sapisid: String,
    val loginInfo: String,
    val visitorData: String? = null,
) {

    /** True only when every cookie that the SAPISIDHASH scheme needs is present. */
    val isComplete: Boolean
        get() = sid.isNotEmpty() && hsid.isNotEmpty() && ssid.isNotEmpty() &&
            apisid.isNotEmpty() && sapisid.isNotEmpty() && loginInfo.isNotEmpty()

    /**
     * `Cookie:` header value for API and media requests.
     *
     * Two extra cookies ride along:
     *  – `SOCS=CAISAiAD` pre-answers the EU consent interstitial (mirrors NewPipe's
     *    `generateConsentCookie()`, "all cookies accepted" variant, which also unlocks
     *    mixes/playlists),
     *  – `VISITOR_INFO1_LIVE` only when we learned one from the wire.
     */
    fun cookieHeader(): String = buildString(256) {
        append("SID=").append(sid)
        append("; HSID=").append(hsid)
        append("; SSID=").append(ssid)
        append("; APISID=").append(apisid)
        append("; SAPISID=").append(sapisid)
        append("; LOGIN_INFO=").append(loginInfo)
        append("; SOCS=").append(CONSENT_ACCEPTED)
        visitorData?.takeIf { it.isNotEmpty() }?.let { append("; VISITOR_INFO1_LIVE=").append(it) }
    }

    /**
     * Same cookies, expressed as OkHttp cookies so that *every* request issued through the
     * shared client — including the ones NewPipeExtractor makes — is authenticated.
     */
    fun toOkHttpCookies(): List<Cookie> {
        val now = System.currentTimeMillis()
        val expiry = now + PERSIST_MILLIS
        fun cookie(name: String, value: String): Cookie? {
            if (value.isEmpty()) return null
            return Cookie.Builder()
                .name(name)
                .value(value)
                .domain("youtube.com")
                .path("/")
                .expiresAt(expiry)
                .build()
        }

        return listOfNotNull(
            cookie("SID", sid),
            cookie("HSID", hsid),
            cookie("SSID", ssid),
            cookie("APISID", apisid),
            cookie("SAPISID", sapisid),
            cookie("LOGIN_INFO", loginInfo),
            cookie("SOCS", CONSENT_ACCEPTED),
        )
    }

    companion object {
        const val CONSENT_ACCEPTED = "CAISAiAD"

        /** Cookies that must all be present for a login to be considered successful. */
        val REQUIRED: List<String> =
            listOf("SID", "HSID", "SSID", "APISID", "SAPISID", "LOGIN_INFO")

        private const val PERSIST_MILLIS = 1000L * 60 * 60 * 24 * 365

        /**
         * Parses a `Cookie:` style string (`"SID=…; HSID=…; …"`) into a [SessionCookies].
         * Returns `null` unless all of [REQUIRED] are present — a partially authenticated
         * session is worse than no session, because InnerTube answers it with confusing
         * "success but empty" responses.
         */
        fun fromHeader(header: String?, visitorData: String? = null): SessionCookies? {
            if (header.isNullOrEmpty()) return null
            if (!header.contains('=')) return null

            var sid: String? = null
            var hsid: String? = null
            var ssid: String? = null
            var apisid: String? = null
            var sapisid: String? = null
            var loginInfo: String? = null
            var visitor: String? = visitorData

            var start = 0
            val length = header.length
            while (start < length) {
                var end = header.indexOf(';', start)
                if (end == -1) end = length
                // Skip a single leading space after ';'.
                var nameStart = start
                while (nameStart < end && header[nameStart] == ' ') nameStart++
                val eq = header.indexOf('=', nameStart)
                if (eq in nameStart until end) {
                    val name = header.substring(nameStart, eq)
                    val value = header.substring(eq + 1, end)
                    when (name) {
                        "SID" -> if (value.length > 2) sid = value
                        "HSID" -> if (value.length > 2) hsid = value
                        "SSID" -> if (value.length > 2) ssid = value
                        "APISID" -> if (value.length > 2) apisid = value
                        "SAPISID" -> if (value.length > 2) sapisid = value
                        "LOGIN_INFO" -> if (value.length > 2) loginInfo = value
                        "VISITOR_INFO1_LIVE" -> if (value.length > 2) visitor = value
                    }
                }
                start = end + 1
            }

            if (sid == null || hsid == null || ssid == null ||
                apisid == null || sapisid == null || loginInfo == null
            ) {
                return null
            }

            return SessionCookies(
                sid = sid!!,
                hsid = hsid!!,
                ssid = ssid!!,
                apisid = apisid!!,
                sapisid = sapisid!!,
                loginInfo = loginInfo!!,
                visitorData = visitor,
            )
        }
    }
}
