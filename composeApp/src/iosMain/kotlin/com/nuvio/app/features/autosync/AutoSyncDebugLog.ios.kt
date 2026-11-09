package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.SubtitleSyncCue

internal actual object AutoSyncDebugLog {
    actual val ENABLED: Boolean get() { AutoSyncPreferencesRepository.ensureLoaded(); return AutoSyncPreferencesRepository.debugLogsEnabled.value }
    actual val VERBOSE: Boolean get() = ENABLED
    private fun emit(message: String) { if (ENABLED) println("NuvioAutoSync: $message") }
    actual fun start(sourceKey: String, subtitleUrl: String) { emit("session started") }
    actual fun section(title: () -> String) { if (ENABLED) emit(title()) }
    actual fun info(message: () -> String) { if (ENABLED) emit(message()) }
    actual fun warn(message: () -> String) { if (ENABLED) emit(message()) }
    actual fun error(throwable: Throwable?, message: () -> String) { if (ENABLED) emit(message() + " " + throwable?.message.orEmpty()) }
    actual fun verbose(message: () -> String) { if (ENABLED) emit(message()) }
    actual fun cue(prefix: String, index: Int, startMs: Long, endMs: Long, text: String) { if (ENABLED) emit("$prefix[$index] ${formatTimestamp(startMs)} --> ${formatTimestamp(endMs)} | $text") }
    actual fun timing(label: String, cues: List<SubtitleSyncCue>, estimatedEndStartsMs: Set<Long>, sdh: Boolean) { if (ENABLED) emit(AutoSyncTimingDump.encode(AutoSyncTimingDump.Track(label, cues, estimatedEndStartsMs, sdh))) }
    actual fun formatTimestamp(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        return listOf(safe / 3600000, safe / 60000 % 60, safe / 1000 % 60).joinToString(":") { it.toString().padStart(2, '0') } + "." + (safe % 1000).toString().padStart(3, '0')
    }
}
