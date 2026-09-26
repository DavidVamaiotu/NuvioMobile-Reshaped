package com.nuvio.app.features.reshaped.livetv

internal expect object LiveTvPlaylistFileBridge {
    fun exportBackup(
        fileName: String,
        payload: String,
        onResult: (Result<String>) -> Unit,
    )

    fun importPlaylist(
        onResult: (Result<String>) -> Unit,
    )
}
