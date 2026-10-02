package com.nuvio.app.features.autosync.bubble

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.concurrent.Volatile

/** A small, soft copy of the video behind the bubble, and where it was taken from (window px). */
internal class BubbleBackdropFrame(val image: ImageBitmap, val windowRect: Rect)

/** Where the bubble sits in the window, in px, before its animations move it. */
internal class BubbleWindowBounds {
    @Volatile
    var rect: Rect? = null

    /** Whether the video should be copied now: while words or the card show, not for the droplet alone. */
    val sampling = MutableStateFlow(true)
}

/** About 30 copies a second: most video is 24 to 30 frames a second, so this catches nearly every frame. */
internal const val BUBBLE_SAMPLE_INTERVAL_MS = 33L

/**
 * Draws a copied frame as refracting glass in a single pass (the pill's lens), filling the bubble
 * of [corner] radius at [alpha]. [copied] is where the frame lies in the bubble's own coordinates.
 */
internal fun interface BubbleBackdropPainter {
    fun DrawScope.paint(frame: BubbleBackdropFrame, copied: Rect, corner: Float, alpha: Float)
}

/**
 * The platform's way to see the video behind the bubble, so the glass can blur and bend it.
 * Installed on Android; without it the bubble draws its plain frosted glass.
 */
internal object AutoSyncBubbleBackdrop {
    var sampler: (@Composable (bounds: BubbleWindowBounds, marginPx: Float) -> State<BubbleBackdropFrame?>)? = null

    /** A one-pass glass painter where the platform has one; otherwise the frame is blurred in its own layer. */
    var painter: (@Composable () -> BubbleBackdropPainter?)? = null
}
