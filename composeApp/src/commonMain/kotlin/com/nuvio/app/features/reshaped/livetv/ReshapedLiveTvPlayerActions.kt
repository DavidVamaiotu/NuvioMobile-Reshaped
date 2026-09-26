package com.nuvio.app.features.reshaped.livetv

import com.nuvio.app.features.player.PlayerScreenRuntime
import com.nuvio.app.features.player.sanitizePlaybackHeaders

/** The sole player state adapter for Reshaped Live TV channel switching. */
internal fun PlayerScreenRuntime.switchToReshapedLiveTvChannel(channel: LiveTvChannel) {
    LiveTvRepository.recordRecentChannel(channel)
    if (channel.streamUrl == activeSourceUrl) {
        showReshapedLiveTvChannelsPanel = false
        controlsVisible = true
        return
    }

    activeSourceUrl = channel.streamUrl
    activeSourceAudioUrl = null
    activeSourceHeaders = sanitizePlaybackHeaders(channel.headers)
    activeSourceResponseHeaders = emptyMap()
    externalSubtitles = emptyList()
    activeStreamType = channel.streamType
    activeSourceIdentityKey = "reshaped-live-tv:${channel.streamUrl}"
    activeStreamTitle = channel.name
    activeStreamSubtitle = channel.group.takeIf(String::isNotBlank)
    activeProviderName = "Live TV"
    activeProviderAddonId = "reshaped-live-tv"
    activeVideoId = null
    activeInitialPositionMs = 0L
    activeInitialProgressFraction = null
    args = args.copy(
        title = channel.name,
        logo = channel.logoUrl,
        streamTitle = channel.name,
        streamSubtitle = channel.group.takeIf(String::isNotBlank),
        sourceUrl = channel.streamUrl,
        sourceHeaders = channel.headers,
        streamType = channel.streamType,
    )
    showReshapedLiveTvChannelsPanel = false
    controlsVisible = true
}
