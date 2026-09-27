package com.nuvio.app.features.reshaped.livetv

/**
 * Programme guide for the channels in the list: a few programmes per channel from now on, keyed
 * by the XMLTV channel id in lower case. The one on air is picked when it is shown, so the
 * "now playing" line moves on by itself as programmes end.
 */
internal typealias LiveTvSchedule = Map<String, List<LiveTvProgramme>>

private const val EPG_LOOKAHEAD_MS = 12L * 60 * 60 * 1000
private const val EPG_MAX_PER_CHANNEL = 4

/**
 * Reads the XMLTV guide at [url] (plain or gzip), keeping only programmes of [channelIds]
 * (lower case) that have not ended yet and start within the next hours. Platforms stream the
 * file where they can, so a large guide never sits in memory as a whole.
 */
internal expect suspend fun loadXmlTvSchedule(
    url: String,
    channelIds: Set<String>,
    nowEpochMs: Long,
): LiveTvSchedule

/** Collects programmes for [channelIds] while a guide is read; see [loadXmlTvSchedule]. */
internal class LiveTvScheduleBuilder(
    private val channelIds: Set<String>,
    private val nowEpochMs: Long,
) {
    private val entries = HashMap<String, MutableList<LiveTvProgramme>>()

    /** [channelId] must already be lower case. */
    fun wants(channelId: String): Boolean = channelId in channelIds

    fun add(channelId: String, title: String, startEpochMs: Long, stopEpochMs: Long) {
        if (stopEpochMs <= startEpochMs || stopEpochMs <= nowEpochMs) return
        if (startEpochMs >= nowEpochMs + EPG_LOOKAHEAD_MS) return
        val list = entries.getOrPut(channelId) { ArrayList(EPG_MAX_PER_CHANNEL) }
        if (list.size >= EPG_MAX_PER_CHANNEL) {
            // Guides are usually in time order; if not, keep the earliest programmes.
            val latest = list.maxBy { it.startEpochMs }
            if (latest.startEpochMs <= startEpochMs) return
            list.remove(latest)
        }
        list += LiveTvProgramme(
            title = title,
            startEpochMs = startEpochMs,
            stopEpochMs = stopEpochMs,
            timeLabel = "${LiveTvClock.formatClock(startEpochMs)} - ${LiveTvClock.formatClock(stopEpochMs)}",
        )
    }

    fun build(): LiveTvSchedule = entries.mapValues { (_, list) -> list.sortedBy { it.startEpochMs } }
}

/** The programme on air at [nowEpochMs] for each channel id in [tvgIds] (as the playlist spells it). */
internal fun currentProgrammes(
    schedule: LiveTvSchedule,
    tvgIds: Collection<String>,
    nowEpochMs: Long,
): Map<String, LiveTvProgramme> {
    if (schedule.isEmpty()) return emptyMap()
    val current = HashMap<String, LiveTvProgramme>()
    for (tvgId in tvgIds) {
        val programme = schedule[tvgId.lowercase()]
            ?.firstOrNull { nowEpochMs >= it.startEpochMs && nowEpochMs < it.stopEpochMs }
            ?: continue
        current[tvgId] = programme
    }
    return current
}

private val xmlTvProgrammeRegex = Regex(
    """<programme\b([^>]*)>([\s\S]*?)</programme>""",
    RegexOption.IGNORE_CASE,
)
private val xmlTvTitleRegex = Regex(
    """<title\b[^>]*>([\s\S]*?)</title>""",
    RegexOption.IGNORE_CASE,
)
private val xmlAttributeRegex = Regex("""([\w-]+)="([^"]*)"""")

/** Guide parsing for platforms without a streaming XML reader: [content] is the whole guide. */
internal fun parseXmlTvSchedule(
    content: String,
    channelIds: Set<String>,
    nowEpochMs: Long,
): LiveTvSchedule {
    val builder = LiveTvScheduleBuilder(channelIds, nowEpochMs)
    xmlTvProgrammeRegex.findAll(content).forEach { match ->
        val attributes = xmlAttributeRegex.findAll(match.groupValues[1])
            .associate { attribute -> attribute.groupValues[1].lowercase() to attribute.groupValues[2] }
        val channelId = attributes["channel"]?.trim()?.lowercase()?.takeIf(builder::wants) ?: return@forEach
        val start = attributes["start"]?.let(LiveTvClock::parseXmlTvTimestamp) ?: return@forEach
        val stop = attributes["stop"]?.let(LiveTvClock::parseXmlTvTimestamp) ?: return@forEach
        val title = xmlTvTitleRegex.find(match.groupValues[2])
            ?.groupValues
            ?.get(1)
            ?.decodeXmlEntities()
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return@forEach
        builder.add(channelId, title, start, stop)
    }
    return builder.build()
}

internal fun String.decodeXmlEntities(): String =
    replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
