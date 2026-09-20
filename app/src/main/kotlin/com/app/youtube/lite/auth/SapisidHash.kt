package com.app.youtube.lite.auth

import java.security.MessageDigest

/**
 * `SAPISIDHASH` — YouTube's per-request request-signing scheme for authenticated InnerTube
 * calls issued outside the browser (there is no OAuth token to present, so the client proves
 * possession of the session cookie by hashing it with the current timestamp).
 *
 * Wire format:
 * ```
 * Authorization: SAPISIDHASH <unixSeconds>_<sha1Hex(unixSeconds + " " + SAPISID + " " + origin)>
 * ```
 *
 * The hash is time-boxed by YouTube (a few minutes of clock skew is tolerated), which is why
 * it is regenerated for every request rather than cached.
 *
 * Pure, dependency-free and unit-tested — see `SapisidHashTest`.
 */
object SapisidHash {

    const val YOUTUBE_ORIGIN = "https://www.youtube.com"
    const val ACCOUNTS_ORIGIN = "https://accounts.google.com"

    private const val HEX = "0123456789abcdef"

    /**
     * Builds the full header value for [sapisid].
     *
     * @param sapisid  value of the `SAPISID` cookie captured during WebView login
     * @param origin   the page origin the request is made "from"
     * @param timestampSeconds Unix time in seconds (injectable for tests)
     */
    @JvmOverloads
    fun authorizationHeader(
        sapisid: String,
        origin: String = YOUTUBE_ORIGIN,
        timestampSeconds: Long = System.currentTimeMillis() / 1000L,
    ): String = "SAPISIDHASH ${timestampSeconds}_${hash(sapisid, origin, timestampSeconds)}"

    /**
     * The raw SHA-1 over `"<timestamp> <sapisid> <origin>"`, lowercase hex.
     * SHA-1 is dictated by the protocol — it is not used here as a security primitive.
     */
    fun hash(sapisid: String, origin: String, timestampSeconds: Long): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(timestampSeconds.toString().toByteArray(Charsets.UTF_8))
        digest.update(SPACE)
        digest.update(sapisid.toByteArray(Charsets.UTF_8))
        digest.update(SPACE)
        digest.update(origin.toByteArray(Charsets.UTF_8))
        return toHex(digest.digest())
    }

    private val SPACE = byteArrayOf(0x20)

    private fun toHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size shl 1)
        var i = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out[i++] = HEX[v ushr 4]
            out[i++] = HEX[v and 0x0F]
        }
        return String(out)
    }
}
