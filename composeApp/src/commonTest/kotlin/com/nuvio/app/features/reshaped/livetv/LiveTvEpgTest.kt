package com.nuvio.app.features.reshaped.livetv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveTvEpgTest {
    private val guide = """
        <tv>
        <programme start="20260926200000 +0000" stop="20260926210000 +0000" channel="News.TV"><title>Evening News</title></programme>
        <programme start="20260926210000 +0000" stop="20260926220000 +0000" channel="News.TV"><title>Late Show &amp; Talk</title></programme>
        <programme start="20260926200000 +0000" stop="20260926210000 +0000" channel="other"><title>Not listed</title></programme>
        </tv>
    """.trimIndent()

    @Test
    fun keepsListedChannelsAndMovesOnAsProgrammesEnd() {
        val at2030 = 1_790_454_600_000L // 2026-09-26 20:30 UTC
        val schedule = parseXmlTvSchedule(guide, channelIds = setOf("news.tv"), nowEpochMs = at2030)

        assertEquals(setOf("news.tv"), schedule.keys)
        assertEquals("Evening News", currentProgrammes(schedule, listOf("News.TV"), at2030)["News.TV"]?.title)
        val at2130 = at2030 + 60 * 60 * 1000L
        assertEquals("Late Show & Talk", currentProgrammes(schedule, listOf("News.TV"), at2130)["News.TV"]?.title)
        assertNull(currentProgrammes(schedule, listOf("News.TV"), at2130 + 60 * 60 * 1000L)["News.TV"])
    }

    @Test
    fun recognisesOnlyHashWrappedHeadings() {
        assertTrue(isLikelyCategoryHeading("#### NEWS ####"))
        assertEquals(false, isLikelyCategoryHeading("Channel #1 HD"))
    }
}
