package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.player.PlaybackBufferSettings
import com.nuvio.app.isIos
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_playback_buffer_auto
import nuvio.composeapp.generated.resources.settings_playback_buffer_default
import nuvio.composeapp.generated.resources.settings_playback_buffer_description
import nuvio.composeapp.generated.resources.settings_playback_buffer_section
import nuvio.composeapp.generated.resources.settings_playback_buffer_title
import nuvio.composeapp.generated.resources.settings_playback_buffer_value_gb
import nuvio.composeapp.generated.resources.settings_playback_memory_buffer_description
import nuvio.composeapp.generated.resources.settings_playback_memory_buffer_title
import org.jetbrains.compose.resources.stringResource

/** Seek buffer: disk cache and memory buffer the way Nuvio TV does them (Android players only). */
@Composable
internal fun PlaybackBufferSettingsSection(isTablet: Boolean) {
    if (isIos) return
    val bufferMb by PlaybackBufferSettings.bufferMb.collectAsStateWithLifecycle()
    val largerMemoryBuffer by PlaybackBufferSettings.largerMemoryBuffer.collectAsStateWithLifecycle()

    SettingsSection(
        title = stringResource(Res.string.settings_playback_buffer_section),
        isTablet = isTablet,
    ) {
        SettingsGroup(isTablet = isTablet) {
            SettingsNavigationRow(
                title = stringResource(
                    Res.string.settings_playback_buffer_title,
                    when (bufferMb) {
                        PlaybackBufferSettings.NUVIO_DEFAULT_MB ->
                            stringResource(Res.string.settings_playback_buffer_default)
                        PlaybackBufferSettings.AUTO_MB ->
                            stringResource(Res.string.settings_playback_buffer_auto)
                        else -> stringResource(Res.string.settings_playback_buffer_value_gb, bufferMb / 1024)
                    },
                ),
                description = stringResource(Res.string.settings_playback_buffer_description),
                isTablet = isTablet,
                onClick = PlaybackBufferSettings::cycle,
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSwitchRow(
                title = stringResource(Res.string.settings_playback_memory_buffer_title),
                description = stringResource(Res.string.settings_playback_memory_buffer_description),
                checked = largerMemoryBuffer,
                isTablet = isTablet,
                onCheckedChange = PlaybackBufferSettings::setLargerMemoryBuffer,
            )
        }
    }
}
