package com.nuvio.app.features.player

import java.io.File
import java.io.IOException
import java.io.InputStream

/** Install MPV assets without replacing persisted font configuration or legacy font files. */
internal fun prepareMpvAssets(
    configDir: File,
    cacheDir: File,
    openAsset: (String) -> InputStream,
    onError: (IOException) -> Unit = {},
) {
    configDir.mkdirs()
    try {
        openAsset("cacert.pem").use { input ->
            val target = File(configDir, "cacert.pem")
            // Keep the established certificate-copy behavior for Android asset streams.
            if (target.length() != input.available().toLong()) {
                target.outputStream().use { input.copyTo(it) }
            }
        }
    } catch (error: IOException) {
        onError(error)
    }
    val fonts = File(configDir, "fonts.conf")
    if (!fonts.exists()) {
        try {
            val cachePath = cacheDir.path.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            fonts.writeText(
                """<fontconfig>
                    |<dir>/system/fonts/</dir>
                    |<dir>/product/fonts/</dir>
                    |<cachedir>$cachePath</cachedir>
                    |<alias><family>serif</family><prefer><family>Noto Serif</family></prefer></alias>
                    |<alias><family>sans-serif</family><prefer><family>Roboto</family><family>Noto Sans</family></prefer></alias>
                    |<alias><family>monospace</family><prefer><family>Droid Sans Mono</family></prefer></alias>
                    |</fontconfig>""".trimMargin(),
            )
        } catch (error: IOException) {
            onError(error)
        }
    }
    // subfont.ttf may still be referenced by an existing user configuration.
}
