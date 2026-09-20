package com.app.youtube.lite.auth

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Session handling is the one place where being lenient is dangerous: a half-parsed session
 * produces InnerTube responses that look successful but come back empty, which reads to the user as
 * "the app is broken" rather than "sign in again". These tests pin the strictness.
 */
class SessionCookiesTest {

    private val fullHeader = "SID=SIDVALUE123; HSID=HSIDVALUE123; SSID=SSIDVALUE123; " +
        "APISID=APISIDVALUE123; SAPISID=SAPISIDVALUE123; LOGIN_INFO=LOGININFOVALUE123"

    @Test
    fun `parses a complete cookie header`() {
        val cookies = SessionCookies.fromHeader(fullHeader)
        assertNotNull(cookies)
        requireNotNull(cookies)
        assertEquals("SIDVALUE123", cookies.sid)
        assertEquals("HSIDVALUE123", cookies.hsid)
        assertEquals("SSIDVALUE123", cookies.ssid)
        assertEquals("APISIDVALUE123", cookies.apisid)
        assertEquals("SAPISIDVALUE123", cookies.sapisid)
        assertEquals("LOGININFOVALUE123", cookies.loginInfo)
        assertTrue(cookies.isComplete)
    }

    @Test
    fun `ignores unrelated cookies and captures the visitor id`() {
        val cookies = SessionCookies.fromHeader(
            "$fullHeader; PREF=tz=UTC; VISITOR_INFO1_LIVE=visitor-42; __Secure-3PSID=other",
        )
        requireNotNull(cookies)
        assertEquals("visitor-42", cookies.visitorData)
    }

    @Test
    fun `explicit visitor data wins over the header`() {
        val cookies = SessionCookies.fromHeader(
            "$fullHeader; VISITOR_INFO1_LIVE=from-header",
            visitorData = "from-request",
        )
        requireNotNull(cookies)
        assertEquals("from-request", cookies.visitorData)
    }

    @Test
    fun `a partial session is rejected outright`() {
        // LOGIN_INFO missing: InnerTube would answer, but as an anonymous client with odd quirks.
        val partial = "SID=SIDVALUE123; HSID=HSIDVALUE123; SSID=SSIDVALUE123; " +
            "APISID=APISIDVALUE123; SAPISID=SAPISIDVALUE123"
        assertNull(SessionCookies.fromHeader(partial))
    }

    @Test
    fun `placeholder values are treated as absent`() {
        // "null" and "" are what a scraping bug produces; both must not be mistaken for a session.
        assertNull(SessionCookies.fromHeader("SID=null; HSID=null; SSID=n; APISID=n; SAPISID=n; LOGIN_INFO=n"))
        assertNull(SessionCookies.fromHeader("SID=; HSID=; SSID=; APISID=; SAPISID=; LOGIN_INFO="))
    }

    @Test
    fun `garbage input never throws`() {
        assertNull(SessionCookies.fromHeader(null))
        assertNull(SessionCookies.fromHeader(""))
        assertNull(SessionCookies.fromHeader("not a cookie header"))
        assertNull(SessionCookies.fromHeader("; ; ;"))
    }

    @Test
    fun `cookie header carries the session, consent and visitor id`() {
        val cookies = SessionCookies.fromHeader(fullHeader, visitorData = "visitor-42")
        requireNotNull(cookies)
        val header = cookies.cookieHeader()
        listOf("SID=", "HSID=", "SSID=", "APISID=", "SAPISID=", "LOGIN_INFO=").forEach { name ->
            assertTrue("expected $name in $header", header.contains(name))
        }
        assertTrue(header.contains("SOCS=${SessionCookies.CONSENT_ACCEPTED}"))
        assertTrue(header.contains("VISITOR_INFO1_LIVE=visitor-42"))
    }

    @Test
    fun `visitor id is omitted when unknown`() {
        val cookies = SessionCookies.fromHeader(fullHeader)
        requireNotNull(cookies)
        assertFalse(cookies.cookieHeader().contains("VISITOR_INFO1_LIVE"))
    }

    @Test
    fun `okhttp cookies are domain cookies usable on every youtube host`() {
        val cookies = SessionCookies.fromHeader(fullHeader)
        requireNotNull(cookies)
        val okhttpCookies = cookies.toOkHttpCookies()
        assertEquals(7, okhttpCookies.size) // six session cookies plus SOCS
        val wwwUrl = "https://www.youtube.com/youtubei/v1/browse".toHttpUrl()
        val musicUrl = "https://music.youtube.com/".toHttpUrl()
        okhttpCookies.forEach { cookie ->
            assertEquals("youtube.com", cookie.domain)
            assertTrue("cookie ${cookie.name} should match www.youtube.com", cookie.matches(wwwUrl))
            assertTrue("cookie ${cookie.name} should match music.youtube.com", cookie.matches(musicUrl))
        }
    }

    @Test
    fun `serialization round-trips the session`() {
        val cookies = SessionCookies.fromHeader(fullHeader, visitorData = "v")
        requireNotNull(cookies)
        val encoded = com.app.youtube.lite.core.util.AppJson.encodeToString(cookies)
        val decoded = com.app.youtube.lite.core.util.AppJson.decodeFromString<SessionCookies>(encoded)
        assertEquals(cookies, decoded)
    }
}
