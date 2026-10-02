package com.nuvio.app.features.player

import android.app.ActivityManager
import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.upstream.DefaultAllocator

/**
 * Applies [PlaybackBufferSettings] to ExoPlayer: the disk cache ([PlaybackDiskCache]) and, when
 * chosen, Nuvio TV's ExoPlayer buffer. libmpv keeps Nuvio's own cache sizes.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal object PlaybackBufferAndroid {
    private const val PREFERENCES = "nuvio_playback_buffer_settings"
    private const val BUFFER_MB_KEY = "playback_buffer_mb"
    private const val LARGER_MEMORY_BUFFER_KEY = "playback_larger_memory_buffer"
    private const val MB = 1024L * 1024L
    private const val GB = 1024L * MB

    // Nuvio's own values (0.5.4 lowered them to 10 s back, 50 s ahead).
    private const val NUVIO_EXO_BACK_BUFFER_MS = 10_000
    private const val NUVIO_EXO_MAX_BUFFER_MS = 50_000

    // Nuvio TV's tuned buffer (NuvioExoPlayerPerformanceHelper): 15 s to 45 s ahead, playback
    // starts and resumes after 3 s, and the back buffer gets at most half of the minimum.
    private const val TV_MIN_BUFFER_MS = 15_000
    private const val TV_MAX_BUFFER_MS = 45_000
    private const val TV_BUFFER_FOR_PLAYBACK_MS = 3_000
    private const val TV_BACK_BUFFER_MS = TV_MIN_BUFFER_MS / 2
    private const val TV_ALLOCATOR_SEGMENT_BYTES = 64 * 1024

    private var totalRamBytes = 0L

    fun initialize(context: Context) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        PlaybackBufferSettings.installPersistence(
            load = { if (preferences.contains(BUFFER_MB_KEY)) preferences.getInt(BUFFER_MB_KEY, 0) else null },
            save = { mb -> preferences.edit().putInt(BUFFER_MB_KEY, mb).apply() },
            loadLargerMemoryBuffer = { preferences.getBoolean(LARGER_MEMORY_BUFFER_KEY, false) },
            saveLargerMemoryBuffer = { enabled -> preferences.edit().putBoolean(LARGER_MEMORY_BUFFER_KEY, enabled).apply() },
        )
        val memoryInfo = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(memoryInfo)
        totalRamBytes = memoryInfo.totalMem
        PlaybackDiskCache.cleanUp(context)
    }

    fun exoLoadControl(): DefaultLoadControl {
        if (!PlaybackBufferSettings.largerMemoryBuffer.value) {
            return DefaultLoadControl.Builder()
                .setBackBuffer(NUVIO_EXO_BACK_BUFFER_MS, true)
                .setBufferDurationsMs(
                    DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                    NUVIO_EXO_MAX_BUFFER_MS,
                    DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                    DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                )
                .build()
        }
        return DefaultLoadControl.Builder()
            .setAllocator(DefaultAllocator(true, TV_ALLOCATOR_SEGMENT_BYTES))
            .setTargetBufferBytes(tvTargetBufferBytes())
            .setBufferDurationsMs(
                TV_MIN_BUFFER_MS,
                TV_MAX_BUFFER_MS,
                TV_BUFFER_FOR_PLAYBACK_MS,
                TV_BUFFER_FOR_PLAYBACK_MS,
            )
            // The byte target bounds everything held, back buffer included, as on TV.
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(TV_BACK_BUFFER_MS, true)
            .build()
    }

    /**
     * Nuvio TV's size for this much RAM. TV keeps the buffer in native memory with Nuvio's own
     * player engine; the phone's stock ExoPlayer keeps it on the Java heap, so it is also held to
     * a third of the heap to leave the rest of the app room.
     */
    private fun tvTargetBufferBytes(): Int {
        val ram = totalRamBytes
        val tierMb = when {
            ram <= 0L -> 200
            ram < 1.15 * GB -> 100
            ram < 2.3 * GB -> 200
            ram < 3.2 * GB -> 500
            ram < 4.8 * GB -> 1000
            ram < 6.8 * GB -> 1600
            else -> 2000
        }
        val heapShare = Runtime.getRuntime().maxMemory() / 3
        val defaultBytes = DefaultLoadControl.DEFAULT_VIDEO_BUFFER_SIZE.toLong() + DefaultLoadControl.DEFAULT_AUDIO_BUFFER_SIZE
        return minOf(tierMb * MB, heapShare).coerceAtLeast(defaultBytes).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
