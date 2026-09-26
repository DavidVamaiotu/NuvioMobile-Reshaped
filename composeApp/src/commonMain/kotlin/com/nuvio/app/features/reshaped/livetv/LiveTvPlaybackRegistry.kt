package com.nuvio.app.features.reshaped.livetv

import kotlin.concurrent.Volatile

/**
 * Stream URLs Live TV has sent to the player, so the fork's playback extras (disk read-ahead,
 * AutoSync prefetch, seek previews, connection speed learning) can leave live channels alone.
 * A read is a memory lookup.
 */
object LiveTvPlaybackRegistry {
    private const val MAX_URLS = 16

    @Volatile private var urls: List<String> = emptyList()

    fun register(url: String) {
        if (url.isBlank()) return
        urls = (urls - url + url).takeLast(MAX_URLS)
    }

    fun isLiveTv(url: String?): Boolean = url != null && url in urls
}
