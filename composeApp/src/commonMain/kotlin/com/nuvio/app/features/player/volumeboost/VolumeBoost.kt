package com.nuvio.app.features.player.volumeboost

import com.nuvio.app.features.player.PlayerAudioLevel
import com.nuvio.app.features.player.PlayerGestureController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Nuvio Reshaped volume boost: the swipe volume bar goes past 100% up to 200%.
 *
 * Levels up to 1.0 drive the system volume exactly as before. Past that the system volume
 * stays at max and the player adds gain: 200% is +10 dB, about twice as loud to the ear.
 * The boost only lasts while the player is open, so the next video never starts loud.
 * Platforms that apply the gain install persistence; elsewhere the bar stays at 100%.
 */
object VolumeBoost {
    const val MAX_LEVEL = 2f
    const val MAX_GAIN_DB = 10f

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _gainDb = MutableStateFlow(0f)
    /** Extra gain the active player applies, 0 to [MAX_GAIN_DB]. */
    val gainDb: StateFlow<Float> = _gainDb.asStateFlow()

    private var save: ((Boolean) -> Unit)? = null

    val isAvailable: Boolean
        get() = save != null

    private val active: Boolean
        get() = _enabled.value && save != null

    /** Top of the volume bar: 2.0 (200%) with boost on, else 1.0. */
    val maxLevel: Float
        get() = if (active) MAX_LEVEL else 1f

    fun installPersistence(load: () -> Boolean, save: (Boolean) -> Unit) {
        _enabled.value = load()
        this.save = save
    }

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        if (!enabled) _gainDb.value = 0f
        save?.invoke(enabled)
    }

    fun resetSession() {
        _gainDb.value = 0f
    }

    fun currentVolume(system: PlayerGestureController): PlayerAudioLevel? {
        val level = system.currentVolume() ?: return null
        if (!active) return level
        // Volume keys lowered the system volume while boosted: the boost no longer applies.
        if (!level.isSystemMax()) {
            _gainDb.value = 0f
            return level
        }
        return level.copy(fraction = 1f + _gainDb.value / MAX_GAIN_DB)
    }

    fun setVolume(system: PlayerGestureController, level: Float): PlayerAudioLevel? {
        if (!active || !level.isFinite() || level <= 1f) {
            _gainDb.value = 0f
            return system.setVolume(level)
        }
        val systemLevel = system.setVolume(1f) ?: return null
        if (!systemLevel.isSystemMax()) {
            _gainDb.value = 0f
            return systemLevel
        }
        val boost = level.coerceAtMost(MAX_LEVEL) - 1f
        _gainDb.value = boost * MAX_GAIN_DB
        return systemLevel.copy(fraction = 1f + boost, isMuted = false)
    }

    private fun PlayerAudioLevel.isSystemMax(): Boolean = fraction >= 0.999f
}
