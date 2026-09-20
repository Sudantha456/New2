package com.app.youtube.lite.core.util

/**
 * Text and number formatting, done by hand.
 *
 * Nothing here uses regex or `String.format` on a hot path. These functions run once per card per
 * scroll frame — a feed of 120 videos formats duration, view count and quality label as each row
 * enters the viewport — so they parse and build strings with plain character arithmetic, which
 * allocates only the result.
 *
 * The `parse*` half is the inverse: YouTube sends counts and durations as *display text*
 * (`"1.2M views"`, `"12:34"`) inside the feed JSON, and nothing else, so the text is the API.
 */
object Formatters {

    // ── Display ──────────────────────────────────────────────────────────────

    /** `1234` → `"0:20"`, `3725000` → `"1:02:05"`. Non-positive input renders as `"0:00"`. */
    fun duration(durationMs: Long): String {
        if (durationMs <= 0L) return "0:00"
        val totalSeconds = durationMs / 1000L
        val seconds = totalSeconds % 60L
        val minutes = (totalSeconds / 60L) % 60L
        val hours = totalSeconds / 3600L
        // Only the *seconds* are always padded. An hour is never zero-padded and minutes are only
        // padded once an hour is present, which is how every player (and YouTube itself) prints
        // times: "1:30", "10:00", "1:02:05".
        val mm = if (hours > 0L && minutes < 10L) {
            buildString(2) { append('0').append(minutes) }
        } else {
            minutes.toString()
        }
        val ss = if (seconds >= 10L) {
            seconds.toString()
        } else {
            buildString(2) { append('0').append(seconds) }
        }
        return if (hours > 0L) "$hours:$mm:$ss" else "$mm:$ss"
    }

    /** Elapsed playback clock: same shape as [duration], never negative. */
    fun clock(positionMs: Long): String = duration(positionMs.coerceAtLeast(0L))

    /** `1_234_567` → `"1.2M"`. Matches YouTube's own abbreviation rules. */
    fun count(count: Long): String = when {
        count < 0L -> ""
        count < 1_000L -> count.toString()
        count < 10_000L -> {
            val tenths = count / 100L
            "${tenths / 10}.${tenths % 10}K"
        }
        count < 1_000_000L -> "${count / 1_000L}K"
        count < 10_000_000L -> {
            val tenths = count / 100_000L
            "${tenths / 10}.${tenths % 10}M"
        }
        count < 1_000_000_000L -> "${count / 1_000_000L}M"
        else -> {
            val tenths = count / 100_000_000L
            "${tenths / 10}.${tenths % 10}B"
        }
    }

    /** `"1.2M views"` → `"1.2M views"` friendly label; the count itself via [count]. */
    fun views(count: Long): String = "${count(count)} views"

    /** `1_234_567` → `"1.2 MiB"`. Binary units, matching what the memory overlay reports. */
    fun bytes(bytes: Long): String {
        when {
            bytes < 1024L -> return "$bytes B"
            bytes < 1024L * 1024L -> return "${bytes / 1024L} KiB"
            bytes < 1024L * 1024L * 1024L -> {
                val tenths = bytes * 10L / (1024L * 1024L)
                return "${tenths / 10}.${tenths % 10} MiB"
            }
            else -> return "${bytes / (1024L * 1024L * 1024L)} GiB"
        }
    }

    /** `"1080p60"`-style label; unknown height renders as `"Auto"`. */
    fun quality(height: Int, fps: Int): String = when {
        height <= 0 -> "Auto"
        fps > 30 -> "${height}p${fps}"
        else -> "${height}p"
    }

    /** Shortens to at most [max] characters on a word boundary, appending an ellipsis. */
    fun ellipsize(text: String, max: Int): String {
        if (text.length <= max) return text
        val cut = text.lastIndexOf(' ', max)
        val end = if (cut > max / 2) cut else max
        return text.substring(0, end).trimEnd() + "…"
    }

    // ── Parsing (the reverse direction: YouTube sends display text) ──────────

    /**
     * `"12:34"` → `754`, `"1:02:05"` → `3725`. Returns **seconds** (the unit the model stores).
     * Unparseable input — `"LIVE"`, live-stream badges, empty strings — yields `0`.
     */
    fun parseDuration(text: String?): Long {
        if (text.isNullOrEmpty()) return 0L
        var seconds = 0L
        var value = 0L
        var seen = false
        for (character in text) {
            when {
                character in '0'..'9' -> {
                    value = value * 10 + (character - '0')
                    seen = true
                }
                character == ':' -> {
                    seconds = (seconds + value) * 60L
                    value = 0L
                }
                seen -> return seconds + value
            }
        }
        return seconds + value
    }

    /**
     * `"1.2M views"` → `1_200_000`, `"1,234 views"` → `1234`, `"No views"` → `0`.
     *
     * The multiplier is applied with integer arithmetic (`1.2M` is parsed as 12 tenths and
     * multiplied by 100 000) so that no floating-point rounding can turn a view count into an
     * off-by-one that shows up in the UI.
     */
    fun parseCount(text: String?): Long {
        if (text.isNullOrEmpty()) return 0L
        val digits = StringBuilder(8)
        var decimalAt = -1
        var multiplier = 1L
        var index = 0
        loop@ while (index < text.length) {
            when (val character = text[index]) {
                in '0'..'9' -> digits.append(character)
                ',' -> Unit // grouping separator: "1,234,567"
                '.', '\u00A0' -> if (character == '.' && decimalAt < 0) decimalAt = digits.length
                ' ' -> Unit // separator before the suffix: "1.2M views", "1 234 views"
                'K', 'k' -> {
                    multiplier = 1_000L
                    index++
                    break@loop
                }
                'M', 'm' -> {
                    multiplier = 1_000_000L
                    index++
                    break@loop
                }
                'B', 'b' -> {
                    multiplier = 1_000_000_000L
                    index++
                    break@loop
                }
                else -> break@loop
            }
            index++
        }
        if (digits.isEmpty()) return 0L
        if (decimalAt < 0 || multiplier == 1L) {
            return (digits.toString().toLongOrNull() ?: 0L) * multiplier
        }
        val integral = digits.substring(0, decimalAt).toLongOrNull() ?: 0L
        val fractionText = digits.substring(decimalAt)
        val fraction = fractionText.toLongOrNull() ?: 0L
        val scale = multiplier / powerOfTen(fractionText.length)
        return integral * multiplier + fraction * scale
    }

    private fun powerOfTen(exponent: Int): Long {
        var result = 1L
        repeat(exponent.coerceIn(0, 9)) { result *= 10L }
        return result
    }
}
