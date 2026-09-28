package com.nuvio.app.features.player.seekpreview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout

internal actual fun createSeekrHttpClient(): HttpClient = HttpClient(OkHttp) {
    expectSuccess = false
    install(HttpTimeout) {
        requestTimeoutMillis = 30_000
        connectTimeoutMillis = 15_000
        socketTimeoutMillis = 30_000
    }
}

internal actual fun openLocalSeekPreviewTrack(cacheKey: String, durationMs: Long): SeekPreviewTrack? =
    com.nuvio.app.features.player.seekpreview.local.LocalPreviewSources.open(cacheKey, durationMs)

/** Sprite sheets are opaque JPEGs, so RGB_565 halves their memory at no visible cost. */
internal actual fun decodeSeekrSpriteSheet(bytes: ByteArray): ImageBitmap? {
    val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}
