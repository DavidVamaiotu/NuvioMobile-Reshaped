package com.nuvio.app.features.player.seekpreview

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Frame width as a share of the width available, so it grows with the screen. */
private const val FrameWidthFraction = 0.25f
private val MinFrameWidth = 200.dp
private val MaxFrameWidth = 360.dp
private const val LingerAfterScrubMs = 1500L
private const val SlideMs = 220

/**
 * The scrub-time preview, Netflix style: the frame of the cue the scrub lands on in the middle,
 * outlined, with the frames before and after it either side (see [SeekPreviewFilmstripRow]).
 *
 * Grid-locked scrubbing (see [SeekPreviewCueStepper]) parks the playhead on the centre frame's
 * own timestamp, so the frame outlined is the frame playback resumes on.
 *
 * @param positionMs the scrub position being previewed.
 * @param active true while the user is scrubbing; the strip lingers briefly after it ends.
 */
@Composable
internal fun SeekPreviewThumbnailStrip(
    session: SeekPreviewSession,
    positionMs: Long,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val activeTrack = session.track

    // Prevent flicker between repeated scrub inputs.
    var lingerVisible by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (active) {
            lingerVisible = true
        } else {
            delay(LingerAfterScrubMs)
            lingerVisible = false
        }
    }

    val offsetMs = session.offsetMs.toLong()
    // On-device tracks fill in while playing; a new revision means frames may have sharpened.
    val revision by (activeTrack?.revision ?: NoRevision).collectAsState()
    var strip by remember(activeTrack) { mutableStateOf<Filmstrip?>(null) }
    // In frames: how far the strip still has to slide to settle on its centre.
    val slide = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // Conflate rapid scrub/nudge changes so only the latest request triggers a lookup.
    val requestFlow = remember(activeTrack) { MutableStateFlow(Triple(positionMs, offsetMs, revision)) }
    LaunchedEffect(activeTrack, positionMs, offsetMs, revision) {
        requestFlow.value = Triple(positionMs, offsetMs, revision)
    }
    LaunchedEffect(activeTrack, lingerVisible) {
        // Only the visible frame drives the preview cue, so a hidden one can't overwrite it.
        if (activeTrack == null || !lingerVisible) return@LaunchedEffect
        // Caching the inputs to the re-centring decision below — the covering cue and which
        // side of its midpoint the position falls — replays that decision exactly, so the cache
        // expires precisely when the centre frame is due to hand over to its successor.
        var cachedCovering: SeekPreviewCue? = null
        var cachedPrefersSuccessor = false
        var cachedOffsetMs: Long? = null
        var cachedRevision: Int? = null
        // Neighbours load on their own, so a scrub moving within the centre cue can't cancel them.
        var fillJob: Job? = null
        requestFlow.collectLatest { (position, offset, rev) ->
            val covering = cachedCovering
            if (offset == cachedOffsetMs &&
                rev == cachedRevision &&
                covering != null &&
                covering.contains(position) &&
                covering.prefersSuccessorFor(position) == cachedPrefersSuccessor
            ) {
                return@collectLatest
            }
            // Single writer for the track's offset, pushed right before the lookup so a nudge
            // is reflected on the very next frame.
            activeTrack.offsetMs = offset
            // Only overwrite on success — keeps the last good frame visible during a fetch.
            val coveringThumbnail = activeTrack.thumbnailFor(position) ?: return@collectLatest
            // Cue times arrive on the preview timeline; undo the offset to compare with playback.
            val coveringCue = SeekPreviewCue(
                startMs = coveringThumbnail.cueStartMs - offset,
                endMs = coveringThumbnail.cueEndMs - offset,
            )

            // A cue's frame is captured at its start, so past the halfway mark the next cue's
            // frame is the closer one.
            val prefersSuccessor = coveringCue.prefersSuccessorFor(position)
            val successor = if (prefersSuccessor) {
                activeTrack.thumbnailFor(coveringCue.endMs)
                    ?.takeIf { it.cueStartMs != coveringThumbnail.cueStartMs }
            } else {
                null
            }
            cachedCovering = coveringCue
            cachedPrefersSuccessor = prefersSuccessor
            cachedOffsetMs = offset
            cachedRevision = rev

            val center = successor ?: coveringThumbnail
            val centerStartMs = center.cueStartMs - offset
            val centerEndMs = center.cueEndMs - offset
            val previous = strip
            val steps = previous?.stepsTo(center.cueStartMs)
            val reusable = previous != null && previous.matches(rev, offset)
            val seeded = Filmstrip.around(center, previous, steps, rev, offset)
            strip = seeded
            session.onPreviewCueResolved(SeekPreviewCue(centerStartMs, centerEndMs))
            if (steps != null && steps != 0) {
                scope.launch {
                    val start = (slide.value + steps)
                        .coerceIn(-FilmstripSideFrames.toFloat(), FilmstripSideFrames.toFloat())
                    slide.snapTo(start)
                    slide.animateTo(0f, tween(SlideMs, easing = FastOutSlowInEasing))
                }
            } else if (steps == null) {
                scope.launch { slide.snapTo(0f) }
            }
            fillJob?.cancel()
            fillJob = scope.launch {
                val filled = activeTrack.fillFilmstrip(seeded, refreshAll = !reusable)
                if (strip === seeded) strip = filled
            }
        }
    }

    AnimatedVisibility(
        visible = lingerVisible && activeTrack != null,
        enter = fadeIn(animationSpec = tween(120)),
        exit = fadeOut(animationSpec = tween(200)),
        modifier = modifier,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            // A share of the width, clamped: a bit bigger than before on phones, much bigger on
            // tablets, and never wider than the space it has.
            val frameWidth = (maxWidth * FrameWidthFraction)
                .coerceIn(MinFrameWidth, MaxFrameWidth)
                .coerceAtMost(maxWidth)
            strip?.let { current ->
                SeekPreviewFilmstripRow(
                    strip = current,
                    slide = slide,
                    frameWidth = frameWidth,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
    }
}

/**
 * Draws one sprite tile straight from its sheet, cropped to fill the box (ContentScale.Crop),
 * so no per-frame bitmap is ever copied out of the sheet.
 */
@Composable
internal fun SeekPreviewFrameImage(
    thumbnail: SeekrThumbnail?,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
) {
    if (thumbnail == null) return
    // A stand-in from a nearby moment is blurred so it reads as "loading", not as the frame.
    Canvas(modifier = if (thumbnail.approximate) modifier.blur(6.dp) else modifier) {
        val dstW = size.width
        val dstH = size.height
        if (dstW <= 0f || dstH <= 0f) return@Canvas
        val srcW = thumbnail.srcSize.width
        val srcH = thumbnail.srcSize.height
        val dstAspect = dstW / dstH
        val srcAspect = srcW.toFloat() / srcH.toFloat()
        var cropX = thumbnail.srcOffset.x
        var cropY = thumbnail.srcOffset.y
        var cropW = srcW
        var cropH = srcH
        if (srcAspect > dstAspect) {
            cropW = (srcH * dstAspect).roundToInt().coerceIn(1, srcW)
            cropX += (srcW - cropW) / 2
        } else if (srcAspect < dstAspect) {
            cropH = (srcW / dstAspect).roundToInt().coerceIn(1, srcH)
            cropY += (srcH - cropH) / 2
        }
        drawImage(
            image = thumbnail.sheet,
            srcOffset = IntOffset(cropX, cropY),
            srcSize = IntSize(cropW, cropH),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(dstW.roundToInt(), dstH.roundToInt()),
            alpha = alpha,
        )
    }
}

private val NoRevision = MutableStateFlow(0)
