package com.nuvio.app.features.reshaped.livetv

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

actual object LiveTvClock {
    private val whitespace = Regex("\\s+")
    private val offsetFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z")
    private val localFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    actual fun nowEpochMs(): Long = System.currentTimeMillis()

    actual fun timeZoneId(): String = ZoneId.systemDefault().id

    /** The device's own short time format (13:00 or 1:00 PM) in its time zone. */
    actual fun formatClock(epochMs: Long): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            .format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    actual fun parseXmlTvTimestamp(value: String): Long? {
        val parts = value.trim().split(whitespace, limit = 2)
        val digits = parts.firstOrNull().orEmpty()
        val normalizedDigits = when (digits.length) {
            12 -> "${digits}00"
            14 -> digits
            else -> return null
        }
        return runCatching {
            if (parts.size > 1) {
                OffsetDateTime.parse(
                    "$normalizedDigits ${parts[1]}",
                    offsetFormatter,
                ).toInstant().toEpochMilli()
            } else {
                LocalDateTime.parse(
                    normalizedDigits,
                    localFormatter,
                ).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }
        }.getOrNull()
    }
}
