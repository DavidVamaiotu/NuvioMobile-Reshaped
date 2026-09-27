package com.nuvio.app.features.library

import com.nuvio.app.core.time.isEpisodeReleaseAired
import com.nuvio.app.core.time.parseEpisodeReleaseEpochMs
import com.nuvio.app.features.details.MetaVideo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryCalendarTest {
    private fun item(id: String) = LibraryItem(id = id, type = "series", name = id, savedAtEpochMs = 1L)
    private fun video(id: String) = MetaVideo(id = id, title = "Episode", released = "2026-09-27", season = 1, episode = 1)
    private fun entry(id: String, time: Long) = LibraryCalendarCacheEntry(time, listOf(LibraryCalendarVideo.from(video(id))))

    @Test
    fun `all 125 series resolve with at most six concurrent requests`() = runTest {
        var active = 0
        var peak = 0
        var calls = 0
        val snapshots = loadLibraryCalendar(
            (1..125).map { item("s$it") }, 1, load = { null }, save = { _, _ -> },
            now = { 1_000L + testScheduler.currentTime },
            fetch = { item ->
                calls++
                active++
                peak = maxOf(peak, active)
                delay(10)
                active--
                listOf(video(item.id))
            },
        ).toList()
        assertEquals(125, calls)
        assertEquals(6, peak)
        assertEquals(125, snapshots.last().eventsByDate.values.flatten().size)
        assertEquals(125, snapshots.last().countsByMonth["2026-09"])
        assertFalse(snapshots.last().isLoading)
        assertTrue(snapshots.size < 10, "Fast completions should be coalesced")
    }

    @Test
    fun `slow first request neither blocks later titles nor progressive display`() = runTest {
        val slow = CompletableDeferred<Unit>()
        val started = mutableListOf<String>()
        val snapshots = mutableListOf<LibraryCalendarSnapshot>()
        val job = launch {
            loadLibraryCalendar((1..12).map { item("s$it") }, 1, { null }, { _, _ -> }, { 1_000L }, { item ->
                started += item.id
                if (item.id == "s1") slow.await()
                listOf(video(item.id))
            }).collect { snapshots += it }
        }
        runCurrent()
        assertEquals(12, started.size)
        assertTrue(snapshots.any { it.isLoading && it.eventsByDate.isNotEmpty() })
        slow.complete(Unit)
        job.join()
        assertEquals(12, snapshots.last().eventsByDate.values.flatten().size)
        assertFalse(snapshots.last().isLoading)
    }

    @Test
    fun `fresh titles survive library reordering additions and duplicate list membership`() = runTest {
        val payload = Json.encodeToString(mapOf("series:a" to entry("a", 1_000L)))
        val fetched = mutableListOf<String>()
        var saved = ""
        val snapshots = loadLibraryCalendar(listOf(item("b"), item("a"), item("a")), 7,
            load = { assertEquals(7, it); payload },
            save = { profile, value -> assertEquals(7, profile); saved = value }, now = { 2_000L },
            fetch = { fetched += it.id; listOf(video(it.id)) },
        ).toList()
        assertEquals(listOf("b"), fetched)
        assertEquals(2, snapshots.last().eventsByDate.values.flatten().size)
        assertEquals(setOf("series:a", "series:b"), Json.decodeFromString<Map<String, LibraryCalendarCacheEntry>>(saved).keys)
    }

    @Test
    fun `failed stale entries are retained without extending freshness and missing titles retry`() = runTest {
        var payload = Json.encodeToString(mapOf("series:a" to entry("a", 1L)))
        val fetched = mutableListOf<String>()
        repeat(2) {
            val result = loadLibraryCalendar(listOf(item("a"), item("b"), item("c")), 1,
                load = { payload }, save = { _, value -> payload = value }, now = { LibraryCalendarCacheTtlMs + 2L },
                fetch = { fetched += it.id; if (it.id == "c") listOf(video("c")) else null },
            ).toList().last()
            assertEquals(2, result.eventsByDate.values.flatten().size)
        }
        assertEquals(2, fetched.count { it == "a" })
        assertEquals(2, fetched.count { it == "b" })
        assertEquals(1, fetched.count { it == "c" })
        assertEquals(1L, Json.decodeFromString<Map<String, LibraryCalendarCacheEntry>>(payload).getValue("series:a").fetchedAtEpochMs)
    }

    @Test
    fun `expired entry is replaced and successful empty metadata is cached`() = runTest {
        var payload = Json.encodeToString(mapOf("series:a" to entry("old", 1L)))
        var calls = 0
        repeat(2) {
            val result = loadLibraryCalendar(listOf(item("a")), 1, { payload }, { _, value -> payload = value },
                { LibraryCalendarCacheTtlMs + 2L }, { calls++; emptyList() },
            ).toList().last()
            assertTrue(result.eventsByDate.isEmpty())
        }
        assertEquals(1, calls)
    }

    @Test
    fun `removed titles are pruned and corrupt storage recovers`() = runTest {
        var payload = Json.encodeToString(mapOf("series:a" to entry("a", 1L), "series:removed" to entry("removed", 1L)))
        loadLibraryCalendar(listOf(item("a")), 1, { payload }, { _, value -> payload = value }, { 2L },
            { error("Fresh entry must not fetch") }).toList()
        assertEquals(setOf("series:a"), Json.decodeFromString<Map<String, LibraryCalendarCacheEntry>>(payload).keys)
        payload = "broken json"
        val recovered = loadLibraryCalendar(listOf(item("a")), 1, { payload }, { _, value -> payload = value }, { 2L },
            { listOf(video("a")) }).toList().last()
        assertEquals(1, recovered.eventsByDate.values.flatten().size)
    }

    @Test
    fun `cancellation stops workers and does not publish or persist late results`() = runTest {
        var active = 0
        var saves = 0
        val snapshots = mutableListOf<LibraryCalendarSnapshot>()
        val job = launch {
            loadLibraryCalendar((1..20).map { item("s$it") }, 1, { null }, { _, _ -> saves++ }, { 1L }, {
                active++
                try { delay(60_000); listOf(video(it.id)) } finally { active-- }
            }).collect { snapshots += it }
        }
        runCurrent()
        assertEquals(6, active)
        job.cancelAndJoin()
        assertEquals(0, active)
        assertEquals(0, saves)
        assertEquals(1, snapshots.size)
    }

    @Test
    fun `timeout and individual failures do not abort other titles`() = runTest {
        val result = loadLibraryCalendar(listOf(item("slow"), item("error"), item("ok")), 1,
            { null }, { _, _ -> }, { 1L }, {
                when (it.id) {
                    "slow" -> { delay(60_000); emptyList() }
                    "error" -> error("Provider failed")
                    else -> listOf(video(it.id))
                }
            },
        ).toList().last()
        assertEquals(30_000L, testScheduler.currentTime)
        assertEquals(listOf("ok"), result.eventsByDate.values.flatten().map { it.item.id })
        assertFalse(result.isLoading)
    }

    @Test
    fun `equivalent timestamp offsets use same local day and future instant is not released`() {
        assertEquals(parseLibraryCalendarDate("2026-09-27T23:00:00Z"), parseLibraryCalendarDate("2026-09-28T02:00:00+03:00"))
        assertEquals("2026-09-27", parseLibraryCalendarDate("2026-09-27")?.iso)
        val release = requireNotNull(parseEpisodeReleaseEpochMs("2026-09-27T23:00:00Z"))
        assertFalse(isEpisodeReleaseAired("2026-09-27T23:00:00Z", release - 1)!!)
        assertTrue(isEpisodeReleaseAired("2026-09-27T23:00:00Z", release)!!)
    }

    @Test
    fun `future cache timestamps are not considered fresh`() {
        assertFalse(entry("a", 10L).isFresh(9L))
        assertTrue(entry("a", 10L).isFresh(10L))
        assertFalse(entry("a", 10L).isFresh(10L + LibraryCalendarCacheTtlMs))
    }
}
