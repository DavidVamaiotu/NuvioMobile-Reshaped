package com.nuvio.app.features.reshaped.livetv

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

data class LiveTvChannel(
    val id: String,
    val name: String,
    val streamUrl: String,
    val tvgId: String? = null,
    val logoUrl: String? = null,
    val group: String = "",
    val headers: Map<String, String> = emptyMap(),
    val streamType: String? = null,
    val stalkerCommand: String? = null,
)

data class LiveTvRecentChannel(
    val streamUrl: String,
    val name: String,
    val logoUrl: String? = null,
    val group: String = "",
    val tvgId: String? = null,
)

data class LiveTvProgramme(
    val title: String,
    val startEpochMs: Long,
    val stopEpochMs: Long,
    val timeLabel: String,
)

data class LiveTvUiState(
    val sourceType: LiveTvSourceType = LiveTvSourceType.M3u,
    val sourceUrl: String = "",
    val stalkerSettings: LiveTvStalkerSettings = LiveTvStalkerSettings(),
    val xtreamSettings: LiveTvXtreamSettings = LiveTvXtreamSettings(),
    val channels: List<LiveTvChannel> = emptyList(),
    val currentProgrammes: Map<String, LiveTvProgramme> = emptyMap(),
    val recentChannel: LiveTvRecentChannel? = null,
    val favoriteUrls: Set<String> = emptySet(),
    val isEpgLoading: Boolean = false,
    val isLoading: Boolean = false,
    val isLoaded: Boolean = false,
    val errorMessage: String? = null,
)

enum class LiveTvSourceType {
    M3u,
    Stalker,
    Xtream,
}

data class LiveTvStalkerSettings(
    val portalUrl: String = "",
    val macAddress: String = "",
    val username: String = "",
    val password: String = "",
) {
    val isConfigured: Boolean
        get() = portalUrl.isNotBlank() && macAddress.isNotBlank()
}

data class LiveTvXtreamSettings(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
) {
    val isConfigured: Boolean
        get() = serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()
}

sealed interface LiveTvIncomingSource {
    data class SourceUrl(val url: String) : LiveTvIncomingSource

    data class PlaylistData(
        val fileName: String,
        val data: String,
    ) : LiveTvIncomingSource

    data class DirectStream(
        val url: String,
        val title: String = "Shared stream",
        val headers: Map<String, String> = emptyMap(),
    ) : LiveTvIncomingSource
}

object LiveTvIncomingSourceRepository {
    private val requestChannel = Channel<LiveTvIncomingSource>(capacity = Channel.BUFFERED)
    val requests: Flow<LiveTvIncomingSource> = requestChannel.receiveAsFlow()

    /**
     * Takes shared text that is a playlist, a playlist link or a direct stream link. Anything
     * else (a web page, a video site link) is left alone; false tells the caller so.
     */
    fun submitText(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return false
        if (trimmed.startsWith("#EXTM3U", ignoreCase = true)) {
            requestChannel.trySend(LiveTvIncomingSource.PlaylistData(fileName = "Shared M3U playlist", data = trimmed))
            return true
        }
        val url = firstSharedUrl(trimmed) ?: return false
        when {
            url.looksLikePlaylistUrl() -> requestChannel.trySend(LiveTvIncomingSource.SourceUrl(url))
            url.looksLikeDirectVideoUrl() -> {
                requestChannel.trySend(
                    LiveTvIncomingSource.DirectStream(
                        url = url,
                        title = url.substringBefore('?').substringAfterLast('/').ifBlank { "Shared stream" },
                    ),
                )
            }
            else -> return false
        }
        return true
    }

    fun submitPlaylistData(fileName: String, data: String) {
        val trimmed = data.trim()
        if (trimmed.isBlank()) return
        requestChannel.trySend(
            LiveTvIncomingSource.PlaylistData(
                fileName = fileName.trim().ifBlank { "Shared M3U playlist" },
                data = trimmed,
            ),
        )
    }
}

private val sharedUrlRegex = Regex("""(?i)https?://[^\s"'<>]+""")

private fun firstSharedUrl(value: String): String? =
    sharedUrlRegex.find(value)?.value?.trim()?.trimEnd(',', '.', ')', ']')

private fun String.looksLikePlaylistUrl(): Boolean {
    val lower = lowercase()
    val path = lower.substringBefore('#').substringBefore('?')
    // Xtream providers hand out playlists as get.php?username=…&type=m3u_plus.
    return path.endsWith(".m3u") || path.endsWith(".m3u8") ||
        (path.endsWith("/get.php") && "type=m3u" in lower)
}

/** A source as Reshaped sync carries it between devices (the TV app's sources are the same). */
data class LiveTvSyncSource(
    val type: LiveTvSourceType,
    /** The M3U link, or the server or portal URL. */
    val url: String,
    val stalker: LiveTvStalkerSettings = LiveTvStalkerSettings(),
    val xtream: LiveTvXtreamSettings = LiveTvXtreamSettings(),
) {
    /** The same as the TV app's: two sources with the same identity are one. */
    val identity: String
        get() = when (type) {
            LiveTvSourceType.M3u -> "m3u|${url.lowercase()}"
            LiveTvSourceType.Xtream -> "xtream|${xtream.serverUrl.lowercase()}|${xtream.username}"
            LiveTvSourceType.Stalker -> "stalker|${stalker.portalUrl.lowercase()}|${stalker.macAddress}"
        }
}
