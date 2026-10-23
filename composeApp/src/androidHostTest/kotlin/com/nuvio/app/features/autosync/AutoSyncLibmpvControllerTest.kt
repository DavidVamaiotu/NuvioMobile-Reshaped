package com.nuvio.app.features.autosync

import android.app.Application
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.reshaped.livetv.LiveTvPlaybackRegistry
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AutoSyncLibmpvControllerTest {
    @BeforeTest
    fun initialize() {
        ProfileRepository.clearInMemory()
        AutoSyncPreferencesRepository.installPersistence(load = { false }, save = {})
    }

    @AfterTest
    fun clearState() {
        AutoSyncPreferencesRepository.installPersistence(load = { false }, save = {})
        AutoSyncSyncedSubtitle.clear()
        ProfileRepository.clearInMemory()
    }

    @Test
    fun disabledAndLivePlaybackAttachOriginalWithoutStartingReferenceRequests() = runTest {
        val calls = mutableListOf<Pair<String, Any?>>()
        val base = recordingController(calls)
        listOf(false, true).forEach { live ->
            val sourceUrl = "https://stream.example/${if (live) "channel" else "movie"}.mkv"
            AutoSyncPreferencesRepository.setPreferredSubtitleAutoSyncOnStart(live)
            if (live) LiveTvPlaybackRegistry.register(sourceUrl)
            val coordinator = LibmpvAutoSyncCoordinator(
                context = RuntimeEnvironment.getApplication(),
                scope = this,
                sourceUrl = sourceUrl,
                sourceHeaders = emptyMap(),
                getSubtitleHeaders = { error("Skipped AutoSync must not start reference work") },
                getPreferredLanguage = { "en" },
            )
            val controller = coordinator.wrap(base) { _, _ -> error("Original subtitle must be kept") }
            (controller as AutoSyncPlayerController).setSubtitleUriWithAutoSync("https://subs.example/original.srt")
            assertFalse(controller.autoSyncRetryState.value.available)
            coordinator.dispose()
        }
        assertEquals(listOf<Pair<String, Any?>>(
            "setSubtitleUri" to "https://subs.example/original.srt",
            "setSubtitleUri" to "https://subs.example/original.srt",
        ), calls)
    }

    @Test
    fun manualDelayAndTrackChangesKeepThePlayerControlsWorking() = runTest {
        val calls = mutableListOf<Pair<String, Any?>>()
        val coordinator = LibmpvAutoSyncCoordinator(
            context = RuntimeEnvironment.getApplication(),
            scope = this,
            sourceUrl = "file:///movie.mkv",
            sourceHeaders = emptyMap(),
            getSubtitleHeaders = { emptyMap() },
            getPreferredLanguage = { "en" },
        )
        val controller = coordinator.wrap(recordingController(calls)) { _, _ -> }
        AutoSyncSyncedSubtitle.mark("https://subs.example/original.srt")
        controller.setSubtitleDelayMs(250)
        assertNull(AutoSyncSyncedSubtitle.url.value)
        controller.selectSubtitleTrack(2)
        controller.seekTo(12_000L)
        assertEquals(listOf<Pair<String, Any?>>(
            "setSubtitleDelayMs" to 250,
            "selectSubtitleTrack" to 2,
            "seekTo" to 12_000L,
        ), calls)
        assertFalse((controller as AutoSyncPlayerController).autoSyncRetryState.value.available)
        coordinator.dispose()
    }

    private fun recordingController(calls: MutableList<Pair<String, Any?>>): PlayerEngineController =
        Proxy.newProxyInstance(
            PlayerEngineController::class.java.classLoader,
            arrayOf(PlayerEngineController::class.java),
        ) { _, method, args ->
            calls += method.name to args?.firstOrNull()
            null
        } as PlayerEngineController
}
