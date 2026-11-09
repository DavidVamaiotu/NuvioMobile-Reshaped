package com.nuvio.app.features.player.volumeboost

import com.nuvio.app.features.player.AUDIO_AMPLIFICATION_MAX_DB
import com.nuvio.app.features.player.AUDIO_AMPLIFICATION_MIN_DB
import kotlin.math.pow

/** mpv's volume is cubic: add the two controls' decibels before converting to percent. */
internal fun mpvVolumeForGains(boostDb: Float, amplificationDb: Int): Double {
    val boost = boostDb.coerceIn(0f, VolumeBoost.MAX_GAIN_DB)
    val amplification = amplificationDb.coerceIn(AUDIO_AMPLIFICATION_MIN_DB, AUDIO_AMPLIFICATION_MAX_DB)
    return 100.0 * 10.0.pow((boost + amplification) / 60.0)
}
