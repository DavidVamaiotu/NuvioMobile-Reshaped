package com.nuvio.app.features.pillnav

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import dev.chrisbanes.haze.HazeState

/**
 * The selected-tab lens as the glass sees it: [bounds] in the pill's own coordinates (null while not placed)
 * and [press], 0 at rest towards 1 while a finger is on the pill. Both are read in the draw phase.
 */
internal class PillGlassLens(val bounds: () -> Rect?, val press: () -> Float)

/**
 * The pill's glass background: refracting liquid glass where the platform supports it, frosted blur elsewhere.
 * Clips itself to the capsule; the liquid glass also swells by [PillGlassLens.press] within its own bounds.
 */
@Composable
internal expect fun PillGlassSurface(hazeState: HazeState?, lens: PillGlassLens?, modifier: Modifier = Modifier)

/** Whether [PillGlassSurface] bends the backdrop itself, so the selection lens only needs a light overlay. */
internal expect fun pillGlassRefracts(hazeState: HazeState?): Boolean
