package com.nuvio.app.features.player.seekpreview

import kotlinx.coroutines.flow.StateFlow

/**
 * On-device thumbnails where playback has already been, Seekr everywhere else.
 *
 * The on-device track only has frames for parts of the film that were buffered, now or on an
 * earlier watch. Where it has an exact frame that frame wins, since it always lines up; where
 * it would only offer a nearby stand-in, the Seekr frame is shown instead. The manual sync
 * offset only moves Seekr frames: the on-device ones are already on playback's timeline, so
 * their cue times are shifted onto the preview timeline here to keep the caller's
 * `cue - offset` conversion right.
 */
internal class HybridSeekPreviewTrack(
    private val local: SeekPreviewTrack,
    private val seekr: SeekrTrack,
) : SeekPreviewTrack {
    override var offsetMs: Long
        get() = seekr.offsetMs
        set(value) {
            seekr.offsetMs = value
        }

    override val revision: StateFlow<Int> get() = local.revision

    override suspend fun thumbnailFor(positionMs: Long): SeekrThumbnail? =
        pick(positionMs, local.thumbnailFor(positionMs))

    override suspend fun sideThumbnailFor(positionMs: Long): SeekrThumbnail? =
        pick(positionMs, local.sideThumbnailFor(positionMs))

    private suspend fun pick(positionMs: Long, localThumbnail: SeekrThumbnail?): SeekrThumbnail? {
        val shift = seekr.offsetMs
        val own = localThumbnail?.let { thumbnail ->
            if (shift == 0L) {
                thumbnail
            } else {
                SeekrThumbnail(
                    sheet = thumbnail.sheet,
                    srcOffset = thumbnail.srcOffset,
                    srcSize = thumbnail.srcSize,
                    cueStartMs = thumbnail.cueStartMs + shift,
                    cueEndMs = thumbnail.cueEndMs + shift,
                    approximate = thumbnail.approximate,
                )
            }
        }
        if (own != null && !own.approximate) return own
        return seekr.thumbnailFor(positionMs) ?: own
    }

    override fun cueAt(positionMs: Long): SeekPreviewCue? =
        local.exactCueAt(positionMs)?.shifted() ?: seekr.cueAt(positionMs) ?: local.cueAt(positionMs)?.shifted()

    private fun SeekPreviewCue.shifted(): SeekPreviewCue {
        val shift = seekr.offsetMs
        return if (shift == 0L) this else SeekPreviewCue(startMs + shift, endMs + shift)
    }

    override fun keyframeNear(positionMs: Long, toleranceMs: Long): Long? =
        local.keyframeNear(positionMs, toleranceMs)

    override suspend fun prefetch() {
        local.prefetch()
        seekr.prefetch()
    }

    override fun close() {
        local.close()
        seekr.close()
    }
}
