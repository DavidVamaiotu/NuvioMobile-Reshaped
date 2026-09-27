package com.nuvio.app.features.reshaped.livetv

import androidx.compose.runtime.Composable
import com.nuvio.app.features.player.PlayerScreenRuntime
import com.nuvio.app.features.player.sanitizePlaybackHeaders
import kotlinx.coroutines.launch

/** The in-player channel list, shown from the player's Channels button. */
@Composable
internal fun PlayerScreenRuntime.ReshapedLiveTvChannelsOverlay() {
    LiveTvChannelsPanel(
        visible = showReshapedLiveTvChannelsPanel,
        currentStreamUrl = activeSourceUrl,
        onChannelSelected = { channel ->
            scope.launch { switchToReshapedLiveTvChannel(channel) }
        },
        onDismiss = {
            showReshapedLiveTvChannelsPanel = false
            controlsVisible = true
        },
    )
}

/**
 * The sole player state adapter for Reshaped Live TV channel switching. [listChannel] is the
 * list's entry; the link it plays from is resolved here.
 */
internal suspend fun PlayerScreenRuntime.switchToReshapedLiveTvChannel(listChannel: LiveTvChannel) {
    LiveTvRepository.recordRecentChannel(listChannel)
    if (listChannel.streamUrl == LiveTvPlaybackRegistry.listUrlFor(activeSourceUrl)) {
        showReshapedLiveTvChannelsPanel = false
        controlsVisible = true
        return
    }
    val channel = LiveTvRepository.prepareForPlayback(listChannel)

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
