package com.nuvio.app.features.reshaped.livetv

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Reshaped-owned visibility setting for the Live TV root tab. */
object LiveTvTabSettings {
    private val mutableEnabled = MutableStateFlow(false)
    val enabled = mutableEnabled.asStateFlow()
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        mutableEnabled.value = LiveTvStorage.loadTabEnabled()
        loaded = true
    }

    fun setEnabled(value: Boolean) {
        ensureLoaded()
        LiveTvStorage.saveTabEnabled(value)
        mutableEnabled.value = value
    }
}
