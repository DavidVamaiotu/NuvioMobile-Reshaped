package com.nuvio.app.features.streams

import android.app.Application
import android.content.Context
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.sync.decodeSyncBoolean
import com.nuvio.app.core.sync.decodeSyncString
import com.nuvio.app.core.sync.encodeSyncBoolean
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StreamBadgeCompatibilityTest {
    @BeforeTest
    fun initialize() {
        ProfileRepository.clearInMemory()
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nuvio_stream_badge_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
        StreamBadgeSettingsStorage.initialize(context)
        StreamBadgeSettingsRepository.clearLocalState()
    }

    @AfterTest
    fun clearState() {
        StreamBadgeSettingsRepository.clearLocalState()
        ProfileRepository.clearInMemory()
    }

    @Test
    fun existingConnectionFitChoiceSurvivesEnablingPlayerBadgesAndReload() {
        val preferences = RuntimeEnvironment.getApplication()
            .getSharedPreferences("nuvio_stream_badge_settings", Context.MODE_PRIVATE)
        preferences.edit().putBoolean(ProfileScopedKey.of("prefer_connection_fit"), false).commit()

        StreamBadgeSettingsRepository.setShowInPlayer(true)
        StreamBadgeSettingsRepository.setPlayerBadgeSource("https://badges.example/pack.json")
        StreamBadgeSettingsRepository.clearLocalState()
        StreamBadgeSettingsRepository.ensureLoaded()

        val settings = StreamBadgeSettingsRepository.uiState.value
        assertFalse(settings.preferConnectionFit)
        assertTrue(settings.showInPlayer)
        assertEquals("https://badges.example/pack.json", settings.playerBadgeSource)
    }

    @Test
    fun syncRoundTripKeepsReshapedAndUpstreamSettingsTogether() {
        StreamBadgeSettingsRepository.setPreferConnectionFit(false)
        StreamBadgeSettingsRepository.setShowInPlayer(true)
        StreamBadgeSettingsRepository.setPlayerBadgeSource("https://badges.example/pack.json")
        val payload = StreamBadgeSettingsStorage.exportToSyncPayload()
        assertEquals(false, payload.decodeSyncBoolean("prefer_connection_fit"))
        assertEquals(true, payload.decodeSyncBoolean("show_badges_in_player"))
        assertEquals("https://badges.example/pack.json", payload.decodeSyncString("player_badge_source"))

        StreamBadgeSettingsRepository.setPreferConnectionFit(true)
        StreamBadgeSettingsRepository.setShowInPlayer(false)
        StreamBadgeSettingsRepository.setPlayerBadgeSource("")
        StreamBadgeSettingsStorage.replaceFromSyncPayload(payload)
        StreamBadgeSettingsRepository.onProfileChanged()

        val settings = StreamBadgeSettingsRepository.uiState.value
        assertFalse(settings.preferConnectionFit)
        assertTrue(settings.showInPlayer)
        assertEquals("https://badges.example/pack.json", settings.playerBadgeSource)
    }

    @Test
    fun olderBackupKeepsConnectionFitAndDefaultsNewFieldsWithinItsProfile() {
        val preferences = RuntimeEnvironment.getApplication()
            .getSharedPreferences("nuvio_stream_badge_settings", Context.MODE_PRIVATE)
        val otherProfileKey = ProfileScopedKey.of("show_badges_in_player", 99)
        preferences.edit().putBoolean(otherProfileKey, true).commit()
        StreamBadgeSettingsRepository.setShowInPlayer(true)
        StreamBadgeSettingsRepository.setPlayerBadgeSource("https://badges.example/pack.json")

        StreamBadgeSettingsStorage.replaceFromSyncPayload(buildJsonObject {
            put("prefer_connection_fit", encodeSyncBoolean(false))
        })
        StreamBadgeSettingsRepository.onProfileChanged()

        val settings = StreamBadgeSettingsRepository.uiState.value
        assertFalse(settings.preferConnectionFit)
        assertFalse(settings.showInPlayer)
        assertEquals("", settings.playerBadgeSource)
        assertTrue(preferences.getBoolean(otherProfileKey, false))
    }
}
