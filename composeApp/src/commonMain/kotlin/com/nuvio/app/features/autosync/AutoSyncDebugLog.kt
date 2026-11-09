package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.SubtitleSyncCue

/** Platform trace output for the shared engine; Android retains report export and clipboard. */
internal expect object AutoSyncDebugLog {
    val ENABLED: Boolean
    val VERBOSE: Boolean
    fun start(sourceKey: String, subtitleUrl: String)
    fun section(title: () -> String)
    fun info(message: () -> String)
    fun warn(message: () -> String)
    fun error(throwable: Throwable? = null, message: () -> String)
    fun verbose(message: () -> String)
    fun cue(prefix: String, index: Int, startMs: Long, endMs: Long, text: String)
    fun timing(label: String, cues: List<SubtitleSyncCue>, estimatedEndStartsMs: Set<Long> = emptySet(), sdh: Boolean = false)
    fun formatTimestamp(ms: Long): String
}

internal fun autoSyncDecimal(value: Double, places: Int): String {
    val scale = (0 until places).fold(1.0) { n, _ -> n * 10.0 }
    return (kotlin.math.round(value * scale) / scale).toString()
}

internal fun fmt(value: Double): String = autoSyncDecimal(value, 4)
