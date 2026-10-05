package com.nuvio.app.features.player.seekpreview

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A set of seek-preview thumbnails queried by playback position: either a Seekr track (sprite
 * sheets from a server) or one generated on the device from the playing stream.
 */
internal interface SeekPreviewTrack {
    /** Signed milliseconds added to a requested position before the lookup (see [SeekrTrack]). */
    var offsetMs: Long

    /** Built from the playing stream itself, so it never needs Preview Sync. */
    val isLocal: Boolean get() = false

    /** Bumped whenever thumbnails are added, so a visible preview re-reads its frames. */
    val revision: StateFlow<Int> get() = StaticRevision

    /** The thumbnail covering [positionMs] (after [offsetMs]) with its cue window, or null. */
    suspend fun thumbnailFor(positionMs: Long): SeekrThumbnail?

    /**
     * Like [thumbnailFor], for a frame shown beside the one being scrubbed to: it does not steer
     * background work (the on-device track decodes nearest the last [thumbnailFor] first).
     */
    suspend fun sideThumbnailFor(positionMs: Long): SeekrThumbnail? = thumbnailFor(positionMs)

    /**
     * The cue window (preview timeline) of the frame [thumbnailFor] would return for
     * [positionMs], without loading the frame, or null when there is none.
     */
    fun cueAt(positionMs: Long): SeekPreviewCue?

    /** Like [cueAt], but only for a frame that really shows [positionMs], not a stand-in. */
    fun exactCueAt(positionMs: Long): SeekPreviewCue? = cueAt(positionMs)

    /**
     * The playing file's keyframe nearest [positionMs] (playback timeline) when one is within
     * [toleranceMs], read from the file's own index; null when unknown. Seeking onto a keyframe
     * is both exact and the fastest seek a player can make.
     */
    fun keyframeNear(positionMs: Long, toleranceMs: Long): Long? = null

    /** Downloads or loads what the track needs ahead of the first lookup. */
    suspend fun prefetch() = Unit

    /** Stops any background work; the track is not used again. */
    fun close() = Unit
}

private val StaticRevision: StateFlow<Int> = MutableStateFlow(0)
