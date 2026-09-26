package com.nuvio.app.features.reshaped.livetv

import kotlin.concurrent.Volatile

/**
 * Stream URLs Live TV has sent to the player, so the fork's playback extras (disk read-ahead,
 * AutoSync prefetch, seek previews, connection speed learning) can leave live channels alone.
 * Also remembers which list entry each one came from: a Stalker link is created per play and
 * differs from the list's URL. Reads are memory lookups.
 */
object LiveTvPlaybackRegistry {
    private const val MAX_URLS = 16

    /** Playback URL to the list entry's URL, most recent last. */
    @Volatile private var entries: List<Pair<String, String>> = emptyList()

    fun register(playbackUrl: String, listUrl: String = playbackUrl) {
        if (playbackUrl.isBlank()) return
        entries = (entries.filterNot { it.first == playbackUrl } + (playbackUrl to listUrl)).takeLast(MAX_URLS)
    }

    fun isLiveTv(url: String?): Boolean = url != null && entries.any { it.first == url }

    /** The list entry's URL for a URL the player is playing (itself when unknown). */
    fun listUrlFor(playbackUrl: String): String =
        entries.lastOrNull { it.first == playbackUrl }?.second ?: playbackUrl
}
