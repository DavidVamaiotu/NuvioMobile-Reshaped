package com.nuvio.app.features.player

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AddonSubtitleLabelCompatibilityTest {
    private val request = SubtitleAddonRequest("https://addon.example/subtitles", "addon.id", "My addon")

    @Test
    fun labelsNameSubtitlesWithoutReplacingLanguageOrSelectionIdentity() = runTest {
        val subtitle = parseAddonSubtitles(
            """{"subtitles":[{"id":"release.commentary","url":"https://example.com/en.srt","lang":"eng","label":"  English commentary  "}]}""",
            request,
        ).single()
        assertEquals("English commentary", subtitle.title)
        assertEquals("release.commentary", subtitle.id)
        assertEquals("release.commentary", subtitle.label)
        assertEquals("en", subtitle.language)
        assertEquals("https://example.com/en.srt", subtitle.url)
        assertEquals("My addon", subtitle.addonName)
    }

    @Test
    fun blankLabelsKeepLanguageFallbackAndMissingIdsKeepDistinctSelectionUrls() = runTest {
        val subtitles = parseAddonSubtitles(
            """{"subtitles":[{"url":"https://example.com/a.srt","lang":"en","label":"  "},{"url":"https://example.com/b.srt","lang":"en"},{"id":"invalid","lang":"en","label":"Missing URL"}]}""",
            request,
        )
        assertEquals(2, subtitles.size)
        assertEquals(listOf("addon.id_0", "addon.id_1"), subtitles.map { it.id })
        subtitles.forEach { assertNull(it.title); assertNull(it.label); assertEquals("en", it.language) }
        val options = buildSubtitleSelectionOptions("en", emptyList(), subtitles)
        assertEquals(subtitles.map { it.url }, options.map { (it as SubtitleSelectionOption.Addon).subtitle.url })
    }
}
