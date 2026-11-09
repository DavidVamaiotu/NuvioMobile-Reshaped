package com.nuvio.app.features.player.volumeboost

import kotlin.test.Test
import kotlin.test.assertEquals

class MpvVolumeGainTest {
    @Test
    fun disabledControlsKeepOriginalVolume() {
        assertEquals(100.0, mpvVolumeForGains(0f, 0), 0.001)
    }

    @Test
    fun eitherControlAppliesTenDecibelsOnTheCubicMpvScale() {
        assertEquals(146.7799, mpvVolumeForGains(10f, 0), 0.001)
        assertEquals(146.7799, mpvVolumeForGains(0f, 10), 0.001)
    }

    @Test
    fun bothControlsKeepTheirGainWhenCombined() {
        assertEquals(215.4435, mpvVolumeForGains(10f, 10), 0.001)
        assertEquals(146.7799, mpvVolumeForGains(0f, 10), 0.001)
        assertEquals(146.7799, mpvVolumeForGains(10f, 0), 0.001)
    }

    @Test
    fun eachControlStaysWithinItsSupportedRange() {
        assertEquals(100.0, mpvVolumeForGains(-10f, -10), 0.001)
        assertEquals(215.4435, mpvVolumeForGains(100f, 100), 0.001)
    }
}
