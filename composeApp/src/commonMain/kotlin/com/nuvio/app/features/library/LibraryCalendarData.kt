package com.nuvio.app.features.library

import com.nuvio.app.core.i18n.localizedMonthName
import com.nuvio.app.core.time.isoEpochDay
import com.nuvio.app.core.time.parseEpisodeReleaseLocalDate
import com.nuvio.app.features.details.MetaVideo

internal data class LibraryCalendarEvent(
    val key: String,
    val date: LibraryCalendarDate,
    val rawReleaseInfo: String,
    val item: LibraryItem,
    val title: String,
    val subtitle: String? = null,
    val imageUrl: String? = null,
    val sortTitle: String = title,
) {
    val normalizedSortTitle = sortTitle.lowercase()
}

internal data class LibraryCalendarDate(
    val year: Int,
    val month: Int,
    val day: Int,
) {
    val iso: String = "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
}

internal data class LibraryCalendarMonth(
    val year: Int,
    val month: Int,
) {
    val key: String = "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}"
    val displayTitle: String = "${localizedMonthName(month)} $year"

    fun previous(): LibraryCalendarMonth =
        if (month == 1) LibraryCalendarMonth(year - 1, 12) else copy(month = month - 1)

    fun next(): LibraryCalendarMonth =
        if (month == 12) LibraryCalendarMonth(year + 1, 1) else copy(month = month + 1)
}

internal fun buildLibraryReleaseCalendarFallbackEvents(items: List<LibraryItem>): List<LibraryCalendarEvent> =
    items
        .asSequence()
        .mapNotNull { item ->
            val rawReleaseInfo = item.releaseInfo?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val date = parseLibraryCalendarDate(rawReleaseInfo) ?: return@mapNotNull null
            LibraryCalendarEvent(
                key = "item:${item.type}:${item.id}:${date.iso}",
                date = date,
                rawReleaseInfo = rawReleaseInfo,
                item = item,
                title = item.name,
                imageUrl = item.banner ?: item.poster,
                sortTitle = item.name,
            )
        }
        .sortedWith(compareBy<LibraryCalendarEvent> { it.date.iso }.thenBy { it.normalizedSortTitle })
        .toList()

internal fun MetaVideo.toLibraryCalendarEvent(item: LibraryItem): LibraryCalendarEvent? {
    val rawReleaseInfo = released?.takeIf { it.isNotBlank() } ?: return null
    val date = parseLibraryCalendarDate(rawReleaseInfo) ?: return null
    val seasonNumber = season?.takeIf { it > 0 }
    val episodeNumber = episode?.takeIf { it > 0 }
    val episodeLabel = when {
        seasonNumber != null && episodeNumber != null -> "S${seasonNumber}E${episodeNumber}"
        episodeNumber != null -> "E$episodeNumber"
        else -> null
    }
    val subtitle = listOfNotNull(episodeLabel, title.takeIf { it.isNotBlank() })
        .joinToString(" - ")
        .takeIf { it.isNotBlank() }
    return LibraryCalendarEvent(
        key = "episode:${item.type}:${item.id}:${season ?: 0}:${episode ?: id}:${date.iso}",
        date = date,
        rawReleaseInfo = rawReleaseInfo,
        item = item,
        title = item.name,
        subtitle = subtitle,
        imageUrl = thumbnail ?: item.banner ?: item.poster,
        sortTitle = "${item.name} ${season ?: 0} ${episode ?: 0} $title",
    )
}

internal fun LibraryItem.isLibrarySeries(): Boolean =
    type.equals("series", ignoreCase = true) ||
        type.equals("tv", ignoreCase = true) ||
        type.equals("show", ignoreCase = true) ||
        type.equals("tvshow", ignoreCase = true)

internal fun parseLibraryCalendarDate(raw: String?): LibraryCalendarDate? {
    val datePart = parseEpisodeReleaseLocalDate(raw) ?: return null
    val parts = datePart.split('-')
    if (parts.size != 3) return null
    val year = parts[0].toIntOrNull()?.takeIf { it in 1000..9999 } ?: return null
    val month = parts[1].toIntOrNull()?.takeIf { it in 1..12 } ?: return null
    val day = parts[2].toIntOrNull()?.takeIf { it in 1..daysInLibraryCalendarMonth(year, month) } ?: return null
    return LibraryCalendarDate(year, month, day)
}

internal fun displayLibraryCalendarEventDate(date: LibraryCalendarDate): String =
    "${localizedMonthName(date.month)} ${date.day}, ${date.year}"

internal fun libraryCalendarCells(month: LibraryCalendarMonth): List<LibraryCalendarDate?> {
    val firstDayOffset = firstLibraryCalendarWeekdayOffset(month.year, month.month)
    val days = daysInLibraryCalendarMonth(month.year, month.month)
    val cells = MutableList<LibraryCalendarDate?>(firstDayOffset) { null }
    for (day in 1..days) {
        cells += LibraryCalendarDate(month.year, month.month, day)
    }
    while (cells.size < 42) {
        cells += null
    }
    return cells
}

internal fun firstLibraryCalendarWeekdayOffset(year: Int, month: Int): Int {
    val epochDay = isoEpochDay(LibraryCalendarDate(year, month, 1).iso)
    val raw = (epochDay + 4L) % 7L
    return if (raw < 0L) (raw + 7L).toInt() else raw.toInt()
}

internal fun daysInLibraryCalendarMonth(year: Int, month: Int): Int =
    when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        2 -> if (isLibraryCalendarLeapYear(year)) 29 else 28
        else -> 30
    }

internal fun isLibraryCalendarLeapYear(year: Int): Boolean =
    (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
