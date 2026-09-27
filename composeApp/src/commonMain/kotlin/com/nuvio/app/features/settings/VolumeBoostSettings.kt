package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.player.volumeboost.VolumeBoost
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_volume_boost_description
import nuvio.composeapp.generated.resources.settings_volume_boost_section
import nuvio.composeapp.generated.resources.settings_volume_boost_title
import org.jetbrains.compose.resources.stringResource

/** Volume boost: lets the swipe volume bar reach 200%. Hidden where it can't be saved. */
@Composable
internal fun VolumeBoostSettingsSection(isTablet: Boolean) {
    if (!VolumeBoost.isAvailable) return
    val enabled by VolumeBoost.enabled.collectAsStateWithLifecycle()
    SettingsSection(
        title = stringResource(Res.string.settings_volume_boost_section),
        isTablet = isTablet,
    ) {
        SettingsGroup(isTablet = isTablet) {
            SettingsSwitchRow(
                title = stringResource(Res.string.settings_volume_boost_title),
                description = stringResource(Res.string.settings_volume_boost_description),
                checked = enabled,
                isTablet = isTablet,
                onCheckedChange = VolumeBoost::setEnabled,
            )
        }
    }
}
