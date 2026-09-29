package com.nuvio.app.features.reshaped.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** One synced value. A null [value] is a deletion, kept for a while so other devices learn of it. */
internal data class SyncEntry(val value: JsonElement?, val time: Long)

/** Sections by name ("settings/tv", "live_tv/1/favorites"), each a map of key to entry. */
internal typealias SyncSections = Map<String, Map<String, SyncEntry>>

/**
 * The synced file: every value carries the time it was last set, and two copies merge key by
 * key, the later edit winning. Merging never drops a key only one side has, so a device that
 * uploads over another's newer file loses nothing: the other device puts its keys back on its
 * next sync.
 */
internal object SyncDoc {
    const val VERSION = 1
    /** Deletions older than this are forgotten; a device offline longer may bring the value back. */
    const val TOMBSTONE_MS = 60L * 24 * 60 * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true }

    class NewerFormatException : Exception("Sync file was written by a newer app version")

    fun encode(sections: SyncSections): String = buildJsonObject {
        put("v", VERSION)
        put("s", buildJsonObject {
            sections.toSortedMap().forEach { (name, entries) ->
                if (entries.isEmpty()) return@forEach
                put(name, buildJsonObject {
                    entries.toSortedMap().forEach { (key, entry) ->
                        put(key, buildJsonObject {
                            entry.value?.let { put("v", it) }
                            put("t", entry.time)
                        })
                    }
                })
            }
        })
    }.toString()

    /** The sections in [text]; empty for a blank or unreadable file. Throws for a newer format. */
    fun decode(text: String?): SyncSections {
        if (text.isNullOrBlank()) return emptyMap()
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyMap()
        val version = (root["v"] as? JsonPrimitive)?.longOrNull ?: return emptyMap()
        if (version > VERSION) throw NewerFormatException()
        val sections = root["s"] as? JsonObject ?: return emptyMap()
        return sections.mapNotNull { (name, value) ->
            val entries = (value as? JsonObject)?.mapNotNull { (key, raw) ->
                val entry = raw as? JsonObject ?: return@mapNotNull null
                val time = entry["t"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                key to SyncEntry(entry["v"], time)
            }?.toMap() ?: return@mapNotNull null
            name to entries
        }.toMap()
    }

    /**
     * Stamps what changed on this device since [base] (the file as last synced): for each
     * section in [current], a new or changed value gets [now] and a value that is gone becomes a
     * deletion at [now]. In a section [base] lacks (the first sync, or a section that was empty),
     * new values get time 0: they are added, but lose to a value the account already has, so a new
     * device's defaults never replace the settings of the others.
     * Sections not in [current] (other devices', other profiles') stay as in [base].
     */
    fun stamp(base: SyncSections, current: Map<String, Map<String, JsonElement>>, now: Long): SyncSections {
        val result = base.toMutableMap()
        current.forEach { (name, values) ->
            val before = base[name].orEmpty()
            // A section this device never synced is what it had before sync: the account's copy wins.
            val newTime = if (name in base) now else 0L
            val entries = HashMap<String, SyncEntry>(maxOf(before.size, values.size) * 2)
            values.forEach { (key, value) ->
                val old = before[key]
                entries[key] = when {
                    old == null -> SyncEntry(value, newTime)
                    old.value == value -> old
                    else -> SyncEntry(value, now)
                }
            }
            before.forEach { (key, old) ->
                if (key !in values) entries[key] = if (old.value == null) old else SyncEntry(null, now)
            }
            if (entries.isEmpty()) result.remove(name) else result[name] = entries
        }
        return result
    }

    /** Both files as one: for each key the later edit; on a tie, [b]'s (sync passes the account's copy as [b]). */
    fun merge(a: SyncSections, b: SyncSections): SyncSections {
        val result = HashMap<String, Map<String, SyncEntry>>(a.size + b.size)
        (a.keys + b.keys).forEach { name ->
            val left = a[name].orEmpty()
            val right = b[name].orEmpty()
            val entries = HashMap<String, SyncEntry>((left.size + right.size) * 2)
            (left.keys + right.keys).forEach { key ->
                val l = left[key]
                val r = right[key]
                entries[key] = when {
                    l == null -> r!!
                    r == null -> l
                    l.time > r.time -> l
                    else -> r
                }
            }
            result[name] = entries
        }
        return result
    }

    /** Drops deletions older than [TOMBSTONE_MS], and sections left empty. */
    fun prune(sections: SyncSections, now: Long): SyncSections =
        sections.mapValues { (_, entries) ->
            entries.filterValues { it.value != null || now - it.time < TOMBSTONE_MS }
        }.filterValues { it.isNotEmpty() }

    /** The values (not deletions) of [section]. */
    fun values(sections: SyncSections, section: String): Map<String, JsonElement> =
        sections[section].orEmpty().mapNotNull { (key, entry) -> entry.value?.let { key to it } }.toMap()

    fun latestTime(sections: SyncSections): Long =
        sections.values.maxOfOrNull { entries -> entries.values.maxOfOrNull(SyncEntry::time) ?: 0L } ?: 0L

    /**
     * The time for this device's edits: its clock, but never before an edit it has already seen,
     * so a TV whose clock is behind still wins over what it saw earlier.
     */
    fun stampTime(clock: Long, vararg seen: SyncSections): Long =
        maxOf(clock, (seen.maxOfOrNull(::latestTime) ?: 0L) + 1)
}
