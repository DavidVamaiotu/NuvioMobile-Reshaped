package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubtitleFormatBadgeTest {
    @Test
    fun styledAndBitmapTracksGetBadgesWithoutExposingUnknownCodecNames() {
        assertEquals(SubtitleFormatBadge.ASS, subtitleFormatBadgeForCodec(" SSA "))
        assertEquals(SubtitleFormatBadge.ASS, subtitleFormatBadgeForCodec("ass"))
        assertEquals(SubtitleFormatBadge.PGS, subtitleFormatBadgeForCodec("hdmv_pgs_subtitle"))
        assertEquals(SubtitleFormatBadge.VOBSUB, subtitleFormatBadgeForCodec("dvdsub"))
        assertEquals(SubtitleFormatBadge.DVB, subtitleFormatBadgeForCodec("dvb_subtitle"))
        assertNull(subtitleFormatBadgeForCodec("subrip"))
        assertNull(subtitleFormatBadgeForCodec(null))
    }

    @Test
    fun addonBadgeKeepsLegacySelectionAndSignedUrlIdentity() {
        val url = "https://example.invalid/subtitle.SSA?signature=keep-me#track"
        val subtitle = AddonSubtitle("legacy", url, "en", "English", addonName = "Addon")
        assertEquals(SubtitleFormatBadge.ASS, subtitle.formatBadge)
        assertEquals(url, subtitle.selectionKey)
        assertTrue(subtitle.matchesSelection("legacy"))
        assertTrue(subtitle.matchesSelection(url))
        assertNull(subtitle.copy(url = "https://example.invalid/subtitle.srt?format=ass").formatBadge)
    }

    @Test
    fun lateFormatMetadataRefreshesTrackRowsWithoutChangingSelectionIdentity() {
        val track = SubtitleTrack(0, "stable-track", "English", language = "en")
        val styled = track.copy(formatBadge = SubtitleFormatBadge.ASS)
        assertNotEquals(subtitleTracksStructureKey(listOf(track)), subtitleTracksStructureKey(listOf(styled)))
        assertEquals(SubtitleSelectionOption.BuiltIn(track).id, SubtitleSelectionOption.BuiltIn(styled).id)
    }
}
