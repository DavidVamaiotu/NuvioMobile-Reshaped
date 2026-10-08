package com.nuvio.app.features.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Seek buffer (Nuvio Reshaped): ExoPlayer's disk cache, set up the way Nuvio TV does it.
 * Persistence is injected by the platform so Nuvio's PlayerSettingsRepository stays untouched.
 * Applies from the next playback. The memory buffer is Nuvio's own setting (Playback settings).
 */
internal object PlaybackDiskCacheSettings {
    /** No disk cache: Nuvio's own behaviour. */
    const val NUVIO_DEFAULT_MB = 0
    /** Disk cache sized from free storage, as Nuvio TV's automatic size. */
    const val AUTO_MB = -1
    val optionsMb = listOf(NUVIO_DEFAULT_MB, AUTO_MB, 1024, 2048, 4096)
    private const val DEFAULT_MB = NUVIO_DEFAULT_MB

    private val _bufferMb = MutableStateFlow(DEFAULT_MB)
    /** Disk cache size in MB, [NUVIO_DEFAULT_MB] or [AUTO_MB]. */
    val bufferMb: StateFlow<Int> = _bufferMb.asStateFlow()

    private var save: (Int) -> Unit = {}

    fun installPersistence(
        load: () -> Int?,
        save: (Int) -> Unit,
    ) {
        this.save = save
        _bufferMb.value = load()?.takeIf { it in optionsMb } ?: DEFAULT_MB
    }

    fun setBufferMb(mb: Int) {
        if (mb !in optionsMb || _bufferMb.value == mb) return
        _bufferMb.value = mb
        save(mb)
    }

    fun cycle() {
        setBufferMb(optionsMb[(optionsMb.indexOf(_bufferMb.value) + 1) % optionsMb.size])
    }
}
