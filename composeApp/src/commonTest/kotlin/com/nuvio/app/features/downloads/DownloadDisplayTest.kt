package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadDisplayTest {
    @Test
    fun completedSizeFallsBackToDownloadedBytesWhenMetadataIsUnknown() {
        val complete = item().copy(status = DownloadStatus.Completed, downloadedBytes = 512L)
        for (unknown in listOf(null, 0L, -1L)) {
            val restored = complete.copy(totalBytes = unknown)
            assertEquals(512L, restored.sizeOnDiskBytes)
            assertFalse(restored.hasKnownSize)
        }
        assertEquals(1024L, complete.copy(totalBytes = 1024L).sizeOnDiskBytes)
        assertTrue(complete.copy(totalBytes = 1024L).hasKnownSize)
    }

    @Test
    fun unfinishedTransfersReportOnlyBytesActuallyStored() {
        for (status in listOf(DownloadStatus.Downloading, DownloadStatus.Paused, DownloadStatus.Failed)) {
            val transfer = item().copy(status = status, downloadedBytes = 128L, totalBytes = 4096L)
            assertEquals(128L, transfer.sizeOnDiskBytes, status.name)
        }
    }

    @Test
    fun posterAndLandscapeArtworkKeepTheirFallbacksForMissingMetadata() {
        val episode = item().copy(poster = "poster", background = "backdrop", episodeThumbnail = "episode")
        assertEquals("poster", episode.posterArtwork())
        assertEquals("episode", episode.landscapeArtwork())
        val missingPoster = episode.copy(poster = "  ")
        assertEquals("backdrop", missingPoster.posterArtwork())
        assertEquals("backdrop", missingPoster.copy(episodeThumbnail = "").landscapeArtwork())
        assertEquals("episode", missingPoster.copy(background = null).posterArtwork())
        assertNull(item().posterArtwork())
        assertNull(item().landscapeArtwork())
    }

    private fun item() = DownloadItem(
        id = "download", contentType = "video", parentMetaId = "tt1234567",
        parentMetaType = "series", videoId = "tt1234567:1:2", title = "Series",
        seasonNumber = 1, episodeNumber = 2, streamTitle = "Stream",
        providerName = "Provider", sourceUrl = "https://example.invalid/video",
        fileName = "episode.mkv", status = DownloadStatus.Downloading,
        createdAtEpochMs = 0L, updatedAtEpochMs = 0L,
    )
}
