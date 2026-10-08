package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.player.PlaybackDiskCacheSettings
import com.nuvio.app.isIos
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_playback_buffer_auto
import nuvio.composeapp.generated.resources.settings_playback_buffer_default
import nuvio.composeapp.generated.resources.settings_playback_buffer_description
import nuvio.composeapp.generated.resources.settings_playback_buffer_section
import nuvio.composeapp.generated.resources.settings_playback_buffer_title
import nuvio.composeapp.generated.resources.settings_playback_buffer_value_gb
import org.jetbrains.compose.resources.stringResource

/** Seek buffer: disk cache the way Nuvio TV does it (Android players only). */
@Composable
internal fun PlaybackDiskCacheSettingsSection(isTablet: Boolean) {
    if (isIos) return
    val bufferMb by PlaybackDiskCacheSettings.bufferMb.collectAsStateWithLifecycle()

    SettingsSection(
        title = stringResource(Res.string.settings_playback_buffer_section),
        isTablet = isTablet,
    ) {
        SettingsGroup(isTablet = isTablet) {
            SettingsNavigationRow(
                title = stringResource(
                    Res.string.settings_playback_buffer_title,
                    when (bufferMb) {
                        PlaybackDiskCacheSettings.NUVIO_DEFAULT_MB ->
                            stringResource(Res.string.settings_playback_buffer_default)
                        PlaybackDiskCacheSettings.AUTO_MB ->
                            stringResource(Res.string.settings_playback_buffer_auto)
                        else -> stringResource(Res.string.settings_playback_buffer_value_gb, bufferMb / 1024)
                    },
                ),
                description = stringResource(Res.string.settings_playback_buffer_description),
                isTablet = isTablet,
                onClick = PlaybackDiskCacheSettings::cycle,
            )
        }
    }
}
