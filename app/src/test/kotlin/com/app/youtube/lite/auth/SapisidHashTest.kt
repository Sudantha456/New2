package com.app.youtube.lite.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `SAPISIDHASH` authorisation scheme is the only thing standing between a stored cookie and an
 * authenticated API call, and it is unforgiving: the timestamp is part of the digest, the space
 * separators are part of the digest, and the origin must not carry a trailing slash. A single
 * character out of place produces a `401` that looks exactly like an expired session.
 *
 * The vectors below were produced independently (a separate SHA-1 implementation) rather than
 * copied from this code, which is the only way the test can actually catch a change in the
 * formula.
 */
class SapisidHashTest {

    @Test
    fun `digest matches the reference vector for youtube origin`() {
        val header = SapisidHash.authorizationHeader(
            sapisid = "SAPISIDVALUE",
            origin = SapisidHash.YOUTUBE_ORIGIN,
            timestampSeconds = 1_712_345_678L,
        )
        // sha1("1712345678 SAPISIDVALUE https://www.youtube.com")
        assertEquals(
            "SAPISIDHASH 1712345678_4f05e0873fef17e52ec2685df6645834da039a2b",
            header,
        )
    }

    @Test
    fun `digest matches the reference vector for accounts origin`() {
        val header = SapisidHash.authorizationHeader(
            sapisid = "abc123",
            origin = SapisidHash.ACCOUNTS_ORIGIN,
            timestampSeconds = 1_700_000_000L,
        )
        // sha1("1700000000 abc123 https://accounts.google.com")
        assertEquals(
            "SAPISIDHASH 1700000000_139ff1c0e2192b3f4bc799d0177f8e311eda1d21",
            header,
        )
    }

    @Test
    fun `empty sapisid still hashes deterministically`() {
        val hash = SapisidHash.hash(
            sapisid = "",
            origin = SapisidHash.YOUTUBE_ORIGIN,
            timestampSeconds = 0L,
        )
        // sha1("0  https://www.youtube.com") — note the double space: the timestamp and the empty
        // credential are both part of the input.
        assertEquals("79111367648cb0a6dee7963aeb45c71a332161e1", hash)
    }

    @Test
    fun `header carries the timestamp prefix the server expects`() {
        val header = SapisidHash.authorizationHeader(
            sapisid = "value",
            timestampSeconds = 42L,
        )
        assertTrue(header.startsWith("SAPISIDHASH 42_"))
        assertEquals("SAPISIDHASH", header.substringBefore(' '))
        // Exactly one underscore separates the timestamp from the digest.
        assertEquals(1, header.count { it == '_' })
    }

    @Test
    fun `origin with a trailing slash is rejected rather than silently wrong`() {
        // A trailing slash changes the digest, so it must fail loudly instead of producing a
        // header the server will reject as unauthorised.
        val header = runCatching {
            SapisidHash.authorizationHeader(sapisid = "x", origin = "https://www.youtube.com/")
        }
        // Either the implementation normalises it or it throws — both are acceptable; silently
        // hashing the wrong string is not.
        val produced = header.getOrNull()
        if (produced != null) {
            assertEquals(
                SapisidHash.authorizationHeader(sapisid = "x", origin = SapisidHash.YOUTUBE_ORIGIN),
                produced,
            )
        }
    }

    @Test
    fun `digest is lowercase hexadecimal and forty characters long`() {
        val hash = SapisidHash.hash("sapisid", SapisidHash.YOUTUBE_ORIGIN, 1L)
        assertEquals(40, hash.length)
        assertEquals(hash, hash.lowercase())
        assertNull(hash.firstOrNull { it !in "0123456789abcdef" })
    }
}
