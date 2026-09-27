package com.nuvio.app.features.reshaped.livetv

import com.nuvio.app.features.player.PlayerLaunch

/**
 * The player launch for a Live TV channel. [channel] is the list's entry: the playable link is
 * resolved here, and the entry is remembered as the last watched channel.
 */
internal suspend fun reshapedLiveTvPlayerLaunch(channel: LiveTvChannel, profileId: Int): PlayerLaunch {
    val playbackChannel = LiveTvRepository.prepareForPlayback(channel)
    LiveTvRepository.recordRecentChannel(channel)
    return PlayerLaunch(
        profileId = profileId,
        title = playbackChannel.name,
        sourceUrl = playbackChannel.streamUrl,
        sourceHeaders = playbackChannel.headers,
        streamType = playbackChannel.streamType,
        logo = playbackChannel.logoUrl,
        streamTitle = playbackChannel.name,
        streamSubtitle = playbackChannel.group.takeIf(String::isNotBlank),
        providerName = "Live TV",
        providerAddonId = "reshaped-live-tv",
        contentType = "live-tv",
        videoId = playbackChannel.id,
        parentMetaId = playbackChannel.id.ifBlank { playbackChannel.streamUrl },
        parentMetaType = "live-tv",
    )
}

/** Handles playlists and streams shared to the app until the caller's scope ends. */
internal suspend fun collectLiveTvIncomingSources(
    onShowTab: () -> Unit,
    onPlay: suspend (LiveTvChannel) -> Unit,
) {
    LiveTvIncomingSourceRepository.requests.collect { source ->
        when (source) {
            is LiveTvIncomingSource.SourceUrl -> {
                LiveTvRepository.load(source.url)
                onShowTab()
            }
            is LiveTvIncomingSource.PlaylistData -> {
                LiveTvRepository.loadLocalPlaylist(source.fileName, source.data)
                onShowTab()
            }
            is LiveTvIncomingSource.DirectStream -> onPlay(
                LiveTvChannel(
                    id = source.url,
                    name = source.title,
                    streamUrl = source.url,
                    headers = source.headers,
                ),
            )
        }
    }
}
