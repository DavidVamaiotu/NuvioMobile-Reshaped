package com.nuvio.app.features.player.volumeboost

import android.content.Context
import android.media.audiofx.LoudnessEnhancer
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.pow
import kotlin.math.roundToInt

private const val TAG = "NuvioVolumeBoost"

internal object VolumeBoostPreferencesAndroid {
    private const val preferencesName = "nuvio_volume_boost_settings"
    private const val enabledKey = "enabled"

    fun initialize(context: Context) {
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        VolumeBoost.installPersistence(
            load = { preferences.getBoolean(enabledKey, true) },
            save = { preferences.edit().putBoolean(enabledKey, it).apply() },
        )
    }
}

/**
 * Applies the boost to ExoPlayer with Android's LoudnessEnhancer on the player's audio session.
 * Its built-in limiter keeps loud scenes from hard clipping, and it costs nothing at 100%:
 * the effect is only created once boost is first used and is disabled again at 0 dB.
 */
@Composable
internal fun ExoVolumeBoostEffect(player: ExoPlayer) {
    val booster = remember(player) { ExoLoudnessBooster(player) }
    DisposableEffect(booster) {
        onDispose { booster.release() }
    }
    LaunchedEffect(booster) {
        VolumeBoost.gainDb.collect { booster.setGainDb(it) }
    }
}

/**
 * mpv volume for a boost in dB. mpv's volume scale is cubic, so +10 dB is volume 147,
 * matching ExoPlayer's gain. Needs volume-max above that ([LIBMPV_VOLUME_MAX]).
 */
internal fun libmpvVolumeForBoost(gainDb: Float): Double =
    100.0 * 10.0.pow(gainDb.coerceIn(0f, VolumeBoost.MAX_GAIN_DB) / 60.0)

internal const val LIBMPV_VOLUME_MAX = "200"

private class ExoLoudnessBooster(private val player: ExoPlayer) : Player.Listener {
    private var enhancer: LoudnessEnhancer? = null
    private var enhancerSessionId = C.AUDIO_SESSION_ID_UNSET
    private var failedSessionId = C.AUDIO_SESSION_ID_UNSET
    private var gainMb = 0
    private var released = false

    init {
        player.addListener(this)
    }

    fun setGainDb(gainDb: Float) {
        gainMb = (gainDb * 100f).roundToInt().coerceAtLeast(0)
        apply()
    }

    override fun onAudioSessionIdChanged(audioSessionId: Int) {
        apply()
    }

    private fun apply() {
        if (released) return
        if (gainMb == 0) {
            enhancer?.let { runCatching { it.setEnabled(false) } }
            return
        }
        val sessionId = player.audioSessionId
        if (sessionId == C.AUDIO_SESSION_ID_UNSET || sessionId == failedSessionId) return
        if (enhancer == null || enhancerSessionId != sessionId) {
            releaseEnhancer()
            enhancer = runCatching { LoudnessEnhancer(sessionId) }
                .onFailure {
                    failedSessionId = sessionId
                    Log.w(TAG, "LoudnessEnhancer unavailable: ${it.message}")
                }
                .getOrNull() ?: return
            enhancerSessionId = sessionId
        }
        enhancer?.let { effect ->
            runCatching {
                effect.setTargetGain(gainMb)
                effect.setEnabled(true)
            }.onFailure { Log.w(TAG, "LoudnessEnhancer failed: ${it.message}") }
        }
    }

    private fun releaseEnhancer() {
        enhancer?.let { runCatching { it.release() } }
        enhancer = null
        enhancerSessionId = C.AUDIO_SESSION_ID_UNSET
    }

    fun release() {
        released = true
        player.removeListener(this)
        releaseEnhancer()
    }
}
