package com.nuvio.app.features.reshaped.livetv

import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity

/** Android share entry point owned by the Reshaped Live TV feature. */
internal object LiveTvIncomingIntent {
    fun accept(activity: AppCompatActivity, intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_SEND) return false
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (uri != null) {
            val text = runCatching {
                activity.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull() ?: return false
            LiveTvIncomingSourceRepository.submitPlaylistData(uri.lastPathSegment.orEmpty(), text)
            return true
        }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (text.isBlank()) return false
        LiveTvIncomingSourceRepository.submitText(text)
        return true
    }
}
