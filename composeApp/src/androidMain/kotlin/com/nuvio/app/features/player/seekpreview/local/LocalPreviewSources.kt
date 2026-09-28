@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player.seekpreview.local

import android.content.Context
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.extractor.ChunkIndex
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.mp4.Mp4Extractor
import com.nuvio.app.features.player.seekpreview.SeekPreviewTrack
import com.nuvio.app.features.reshaped.livetv.LiveTvPlaybackRegistry
import kotlin.math.abs

/** The stream an ExoPlayer is showing, as far as on-device previews need it. */
internal class LocalPreviewSource(
    val sourceKey: String,
    val context: Context,
    /** Read on the main thread when a track is opened; live streams have no fixed timeline. */
    val isLive: () -> Boolean = { false },
) {
    @Volatile var released = false
    @Volatile var track: LocalPreviewTrack? = null

    /**
     * False while the viewer has paused. Keyframes are only decoded then (or while scrubbing):
     * a software decode of a large keyframe takes every core for a moment, which drops frames.
     */
    @Volatile var playbackActive = true
        set(value) {
            field = value
            if (!value) track?.onPlaybackPaused()
        }

    /** Stops following the player's play/pause state; called by [LocalPreviewSources.unregister]. */
    var detach: (() -> Unit)? = null
}

/**
 * Connects the player to on-device seek previews. The player side makes two calls:
 * [wrapExtractors] (so playback's own keyframes become thumbnails) and [register]/[unregister]
 * around each stream. The preview session then asks [open] for a track.
 */
internal object LocalPreviewSources {
    @Volatile private var current: LocalPreviewSource? = null

    /** The index of the stream demuxed last, with the stream it belongs to. */
    @Volatile private var keyframeIndex: Pair<String, SeekMap>? = null

    /** Call on the player's thread. */
    fun register(context: Context, sourceUrl: String, player: Player): LocalPreviewSource {
        val source = LocalPreviewSource(
            sourceKey = sourceUrl,
            context = context.applicationContext,
            isLive = { player.isCurrentMediaItemLive },
        )
        source.playbackActive = player.playWhenReady
        val listener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                source.playbackActive = playWhenReady
            }
        }
        player.addListener(listener)
        source.detach = { player.removeListener(listener) }
        current?.takeIf { it.sourceKey != sourceUrl }?.let { stale -> stale.track?.close() }
        current = source
        return source
    }

    fun unregister(source: LocalPreviewSource) {
        source.released = true
        source.detach?.invoke()
        source.detach = null
        source.track?.close()
        source.track = null
        if (current === source) current = null
    }

    /** A track for the stream now playing, or null when no ExoPlayer stream is registered. */
    fun open(cacheKey: String, durationMs: Long): SeekPreviewTrack? {
        val source = current?.takeIf { !it.released } ?: return null
        source.track?.close()
        source.track = null
        // A live window slides and its keyframe times are not playback positions: no previews.
        if (LiveTvPlaybackRegistry.isLiveTv(source.sourceKey) || source.isLive()) return null
        val track = LocalPreviewTrack(source, cacheKey, durationMs) { source.playbackActive }
        source.track = track
        track.start()
        return track
    }

    /** Copies the video keyframes of [sourceKey]'s stream to its preview track while demuxed. */
    fun wrapExtractors(factory: ExtractorsFactory, sourceKey: String): ExtractorsFactory =
        VideoKeyframeExtractorsFactory(factory, object : KeyframeSink {
            private fun track() = current?.takeIf { it.sourceKey == sourceKey }?.track

            override fun wantsKeyframes(): Boolean = track()?.wantsKeyframes() == true

            override fun wantsKeyframe(timeUs: Long): Boolean = track()?.wantsKeyframe(timeUs) == true

            override fun onKeyframe(format: Format, timeUs: Long, data: ByteArray, offset: Int, size: Int) {
                track()?.onKeyframe(format, timeUs, data, offset, size)
            }

            override fun onSeekMap(seekMap: SeekMap) {
                // Only indexes that list real keyframes: an MP4's sync-sample table, or the
                // cue points of an MKV (or a fragmented MP4's segment index). Estimated maps
                // (constant bitrate, binary search) would point between keyframes.
                if (seekMap is Mp4Extractor || seekMap is ChunkIndex) keyframeIndex = sourceKey to seekMap
            }
        })

    /**
     * The keyframe of [sourceKey]'s stream nearest [positionMs] when one is within
     * [toleranceMs], from the stream's own index; null without a usable index.
     */
    fun keyframeNear(sourceKey: String, positionMs: Long, toleranceMs: Long): Long? {
        val (key, seekMap) = keyframeIndex ?: return null
        if (key != sourceKey || positionMs < 0L) return null
        return runCatching {
            val points = seekMap.getSeekPoints(positionMs * 1_000L)
            listOf(points.first.timeUs, points.second.timeUs)
                .map { it / 1_000L }
                .filter { it >= 0L && abs(it - positionMs) <= toleranceMs }
                .minByOrNull { abs(it - positionMs) }
        }.getOrNull()
    }
}
