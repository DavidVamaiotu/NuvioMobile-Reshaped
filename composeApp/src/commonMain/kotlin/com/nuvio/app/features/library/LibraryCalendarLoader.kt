package com.nuvio.app.features.library

import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val LibraryCalendarCacheTtlMs = 2L * 60 * 60 * 1_000
private const val WorkerCount = 6
private val calendarJson = Json { ignoreUnknownKeys = true }

internal data class LibraryCalendarSnapshot(
    val eventsByDate: Map<String, List<LibraryCalendarEvent>> = emptyMap(),
    val countsByMonth: Map<String, Int> = emptyMap(),
    val isLoading: Boolean = true,
)

/** One bounded payload per profile; adding/reordering titles preserves individual freshness. */
@Serializable
internal data class LibraryCalendarCacheEntry(
    val fetchedAtEpochMs: Long,
    val videos: List<LibraryCalendarVideo>,
) {
    fun isFresh(now: Long): Boolean = now >= fetchedAtEpochMs && now - fetchedAtEpochMs < LibraryCalendarCacheTtlMs
}

/** Persist only the fields the calendar renders, not whole detail screens and episode overviews. */
@Serializable
internal data class LibraryCalendarVideo(
    val id: String,
    val title: String,
    val released: String?,
    val thumbnail: String?,
    val season: Int?,
    val episode: Int?,
) {
    fun toVideo() = MetaVideo(id = id, title = title, released = released, thumbnail = thumbnail, season = season, episode = episode)

    companion object {
        fun from(video: MetaVideo) = LibraryCalendarVideo(video.id, video.title, video.released, video.thumbnail, video.season, video.episode)
    }
}

internal fun libraryCalendarItemKey(item: LibraryItem): String = "${item.type.trim().lowercase()}:${item.id.trim()}"

/** Caller runs this flow on Default; tests inject storage, clock, and network. No global calendar state. */
internal fun loadLibraryCalendar(
    items: List<LibraryItem>,
    profileId: Int,
    load: (Int) -> String? = LibraryStorage::loadCalendarPayload,
    save: (Int, String) -> Unit = LibraryStorage::saveCalendarPayload,
    now: () -> Long = LibraryClock::nowEpochMs,
    fetch: suspend (LibraryItem) -> List<MetaVideo>? = { item ->
        MetaDetailsRepository.fetch(item.type, item.id, cacheResult = false, bypassCache = true)?.videos
    },
): Flow<LibraryCalendarSnapshot> = flow {
    val uniqueItems = items.distinctBy(::libraryCalendarItemKey)
    val series = uniqueItems.filter { it.isLibrarySeries() }
    val seriesKeys = series.map(::libraryCalendarItemKey).toSet()
    val cached = runCatching {
        load(profileId)?.let { calendarJson.decodeFromString<Map<String, LibraryCalendarCacheEntry>>(it) }
    }.getOrNull().orEmpty()
    val entries = cached.filterKeys { it in seriesKeys }.toMutableMap()
    val eventsByItem = uniqueItems.associate { item ->
        libraryCalendarItemKey(item) to calendarEvents(item, entries[libraryCalendarItemKey(item)])
    }.toMutableMap()
    val pending = series.filter { entries[libraryCalendarItemKey(it)]?.isFresh(now()) != true }

    fun snapshot(loading: Boolean): LibraryCalendarSnapshot {
        val byDate = eventsByItem.values.flatten().groupBy { it.date.iso }.mapValues { (_, events) ->
            events.sortedWith(compareBy<LibraryCalendarEvent> { it.normalizedSortTitle }.thenBy { it.key })
        }
        val monthCounts = mutableMapOf<String, Int>()
        byDate.forEach { (date, events) ->
            val month = date.take(7)
            monthCounts[month] = (monthCounts[month] ?: 0) + events.size
        }
        return LibraryCalendarSnapshot(byDate, monthCounts, loading)
    }
    emit(snapshot(pending.isNotEmpty()))
    if (pending.isNotEmpty()) {
        coroutineScope {
            val work = Channel<LibraryItem>(WorkerCount)
            val results = Channel<Pair<LibraryItem, List<MetaVideo>?>>(WorkerCount)
            launch {
                try { pending.forEach { work.send(it) } } finally { work.close() }
            }
            repeat(minOf(WorkerCount, pending.size)) {
                launch {
                    for (item in work) {
                        val videos = try {
                            withTimeoutOrNull(30_000L) { fetch(item) }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                        results.send(item to videos)
                    }
                }
            }
            var lastPublish = Long.MIN_VALUE
            repeat(pending.size) {
                val (item, videos) = results.receive()
                if (videos != null) {
                    val key = libraryCalendarItemKey(item)
                    val entry = LibraryCalendarCacheEntry(now(), videos.map(LibraryCalendarVideo::from))
                    entries[key] = entry
                    eventsByItem[key] = calendarEvents(item, entry)
                    // Publish the first success immediately, then coalesce bursts of fast responses.
                    val time = now()
                    if (lastPublish == Long.MIN_VALUE || time - lastPublish >= 200L) {
                        emit(snapshot(true))
                        lastPublish = time
                    }
                }
            }
            results.close()
        }
    }
    // Failed titles keep their old timestamps and are retried on the next open/refresh.
    // Replacing this single payload also prunes removed titles instead of leaking cache generations.
    if (entries != cached) runCatching { save(profileId, calendarJson.encodeToString(entries)) }
    emit(snapshot(false))
}

private fun calendarEvents(item: LibraryItem, entry: LibraryCalendarCacheEntry?): List<LibraryCalendarEvent> {
    val episodes = entry?.videos.orEmpty().mapNotNull { it.toVideo().toLibraryCalendarEvent(item) }.distinctBy { it.key }
    return episodes.ifEmpty { buildLibraryReleaseCalendarFallbackEvents(listOf(item)) }
}
