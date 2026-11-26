package ua.olex.music.model

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object RetryAfter {
    /** HTTP Retry-After may be a delta in seconds or an RFC 1123 date. */
    fun milliseconds(header: String?, wallClock: Long): Long? {
        val value = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        value.toLongOrNull()?.let { seconds -> return seconds.takeIf { it in 0..Long.MAX_VALUE / 1_000 }?.times(1_000) }
        val deadline = runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
            .getOrNull() ?: return null
        return if (deadline <= wallClock) 0 else runCatching { Math.subtractExact(deadline, wallClock) }.getOrDefault(Long.MAX_VALUE)
    }
}
