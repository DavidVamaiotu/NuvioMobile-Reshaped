package com.nuvio.app.features.reshaped.livetv

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.appcompat.app.AppCompatActivity

/** Android share entry point owned by the Reshaped Live TV feature. */
internal object LiveTvIncomingIntent {
    private const val TAG = "LiveTvIncomingIntent"
    /** Larger shared files are not playlists anyone meant to import. */
    private const val MAX_PLAYLIST_BYTES = 64L * 1024 * 1024

    fun accept(activity: AppCompatActivity, intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_SEND) return false
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (uri != null) {
            val resolver = activity.applicationContext.contentResolver
            // Read off the main thread: a playlist file can be megabytes.
            Thread({
                runCatching {
                    val size = runCatching {
                        resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
                    }.getOrNull() ?: -1L
                    if (size > MAX_PLAYLIST_BYTES) return@runCatching
                    val text = resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: return@runCatching
                    LiveTvIncomingSourceRepository.submitPlaylistData(uri.lastPathSegment.orEmpty(), text)
                }.onFailure { Log.w(TAG, "shared playlist unreadable", it) }
            }, "NuvioLiveTvImport").apply { isDaemon = true }.start()
            return true
        }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (text.isBlank()) return false
        // Only playlists and stream links; other shared text (web pages) opens the app as usual.
        return LiveTvIncomingSourceRepository.submitText(text)
    }
}
