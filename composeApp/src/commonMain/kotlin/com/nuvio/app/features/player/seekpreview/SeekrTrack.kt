package com.nuvio.app.features.player.seekpreview

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A preview thumbnail: the sprite [sheet] plus the region of it holding this frame, together
 * with the resolved cue window it was drawn from.
 *
 * [cueStartMs] is the timestamp the frame represents, not the exact position asked for — a
 * request for 163_174 may resolve to a cue covering 160_000..170_000. It is the cue *grid*
 * time today; the generator snaps extraction to a keyframe within ±3s of it.
 */
internal class SeekrThumbnail(
    val sheet: ImageBitmap,
    val srcOffset: IntOffset,
    val srcSize: IntSize,
    val cueStartMs: Long,
    val cueEndMs: Long,
    /** A stand-in from a nearby moment while the real frame is still being generated. */
    val approximate: Boolean = false,
)

/**
 * The full set of preview thumbnails for one title, queried by playback position. Held for the
 * whole playback session.
 *
 * ### Preview sync offset
 * Sprite sheets are generated from one release of a title, but the release being played can
 * have a different head (a distributor logo, black frames). The backend never rescales the
 * preview timeline, so this shows up as a constant offset — conceptually a subtitle delay.
 * [offsetMs] is added to the position *before* the cue lookup: **negative** when the played
 * release has extra head content (a scene at 10:00 in the source sits at 10:30 in playback →
 * `-30_000`), **positive** for the reverse. In both directions the value is
 * `sourceDurationMs - durationMs`.
 */
internal class SeekrTrack(
    private val cues: List<SeekrVttCue>,
    /** Duration of the media the sprites were generated from; 0 when the backend omits it. */
    val sourceDurationMs: Long = 0,
    /** Timebase scale reported by the backend, already applied server-side. Diagnostics only. */
    val scale: Double = 1.0,
    private val sheets: SeekrSheetCache,
) : SeekPreviewTrack {
    /** Signed milliseconds added to a requested position before the cue lookup. Safe to set from the UI. */
    @Volatile
    override var offsetMs: Long = 0L

    val isEmpty: Boolean get() = cues.isEmpty()

    /** All distinct sprite-sheet URLs referenced by this track, in cue order. */
    val sheetUrls: Set<String> get() = cues.mapTo(LinkedHashSet()) { it.tile.sheetUrl }

    /** Downloads every sprite sheet in parallel so later lookups never wait on the network. */
    override suspend fun prefetch() = prefetchSheets()

    suspend fun prefetchSheets() {
        coroutineScope {
            sheetUrls.map { url -> async { sheets.prefetch(url) } }.awaitAll()
        }
    }

    /** The thumbnail covering [positionMs] (after [offsetMs]) with its cue window, or null. */
    override suspend fun thumbnailFor(positionMs: Long): SeekrThumbnail? {
        val cue = resolveCue(positionMs) ?: return null
        val sheet = sheets.get(cue.tile.sheetUrl) ?: return null
        val tile = cue.tile
        val tileW = tile.w.takeIf { it > 0 } ?: 320
        val tileH = tile.h.takeIf { it > 0 } ?: 180
        val x = tile.x.coerceIn(0, (sheet.width - 1).coerceAtLeast(0))
        val y = tile.y.coerceIn(0, (sheet.height - 1).coerceAtLeast(0))
        val w = tileW.coerceAtMost((sheet.width - x).coerceAtLeast(1))
        val h = tileH.coerceAtMost((sheet.height - y).coerceAtLeast(1))
        return SeekrThumbnail(
            sheet = sheet,
            srcOffset = IntOffset(x, y),
            srcSize = IntSize(w, h),
            cueStartMs = cue.startMs,
            cueEndMs = cue.endMs,
        )
    }

    override fun cueAt(positionMs: Long): SeekPreviewCue? =
        resolveCue(positionMs)?.let { SeekPreviewCue(it.startMs, it.endMs) }

    override fun close() = sheets.close()

    /**
     * The last cue whose start is at or before [positionMs] + [offsetMs]. Positions before the
     * first cue or past the last clamp to the first or last cue, so the offset alone never
     * produces null; null only when the track is empty.
     */
    internal fun resolveCue(positionMs: Long): SeekrVttCue? {
        if (cues.isEmpty()) return null
        val correctedPositionMs = positionMs + offsetMs
        var lo = 0
        var hi = cues.size - 1
        var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].startMs <= correctedPositionMs) {
                idx = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return if (idx >= 0) cues[idx] else cues.first()
    }
}

/**
 * Sprite sheets keyed by URL. Every sheet's encoded bytes are kept for the session so
 * scrubbing never re-downloads, while only the most recently used [maxDecodedSheets] are held
 * decoded — a film's worth of full-size decoded sheets is too much memory for a phone.
 *
 * A sheet is downloaded and decoded in this cache's own scope, not the caller's: the preview
 * cancels its lookup whenever the scrub moves on, and a decode thrown away half-way would be
 * started again by the next lookup, so a fast drag could keep the old frame on screen until the
 * finger slowed down. One load per URL runs at a time; different sheets load in parallel.
 */
internal class SeekrSheetCache(
    private val download: suspend (String) -> ByteArray?,
    private val decode: (ByteArray) -> ImageBitmap? = ::decodeSeekrSpriteSheet,
    private val maxDecodedSheets: Int = 8,
) {
    private val guard = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val encoded = mutableMapOf<String, ByteArray>()
    private val downloads = mutableMapOf<String, Deferred<ByteArray?>>()
    private val decodes = mutableMapOf<String, Deferred<ImageBitmap?>>()
    private val decoded = LinkedHashMap<String, ImageBitmap>()

    suspend fun prefetch(url: String) {
        bytesFor(url)
    }

    suspend fun get(url: String): ImageBitmap? {
        val load = guard.withLock {
            decoded.remove(url)?.let { bitmap ->
                decoded[url] = bitmap
                return bitmap
            }
            decodes.getOrPut(url) {
                scope.async {
                    try {
                        val bytes = bytesFor(url) ?: return@async null
                        val bitmap = runCatching { decode(bytes) }.getOrNull() ?: return@async null
                        guard.withLock {
                            decoded[url] = bitmap
                            while (decoded.size > maxDecodedSheets) decoded.remove(decoded.keys.first())
                        }
                        bitmap
                    } finally {
                        withContext(NonCancellable) { guard.withLock { decodes.remove(url) } }
                    }
                }
            }
        }
        return load.await()
    }

    private suspend fun bytesFor(url: String): ByteArray? {
        val load = guard.withLock {
            encoded[url]?.let { return it }
            downloads.getOrPut(url) {
                scope.async {
                    try {
                        download(url)?.also { bytes -> guard.withLock { encoded[url] = bytes } }
                    } finally {
                        withContext(NonCancellable) { guard.withLock { downloads.remove(url) } }
                    }
                }
            }
        }
        return load.await()
    }

    fun close() {
        scope.cancel()
    }
}
