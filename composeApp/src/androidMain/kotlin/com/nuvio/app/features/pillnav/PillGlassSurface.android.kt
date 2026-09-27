package com.nuvio.app.features.pillnav

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import com.nuvio.app.core.ui.glass.GlassBarSurface
import com.nuvio.app.core.ui.nuvio
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

private val GlassSurfaceColor = Color(0xFF1C1C1E)

// Liquid glass (Android 13+): a light blur so the lens edge bends real detail, tinted by the app accent.
@Composable
internal actual fun PillGlassSurface(hazeState: HazeState?, lens: PillGlassLens?, modifier: Modifier) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && hazeState?.blurEnabled == true) {
        TintedRefractedGlass(hazeState, lens, MaterialTheme.nuvio.colors.accent, modifier)
    } else {
        GlassBarSurface(hazeState = hazeState, modifier = modifier, glowStrength = 1f)
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun TintedRefractedGlass(hazeState: HazeState, lens: PillGlassLens?, tint: Color, modifier: Modifier) {
    val shader = remember { RuntimeShader(PillGlassShader) }
    Box(
        modifier
            .layout { measurable, constraints ->
                val outset = 24.dp.roundToPx()
                val placeable = measurable.measure(constraints.offset(outset * 2, outset * 2))
                layout(placeable.width - outset * 2, placeable.height - outset * 2) {
                    placeable.place(-outset, -outset)
                }
            }
            .graphicsLayer {
                val outset = 24.dp.roundToPx().toFloat()
                shader.setFloatUniform("resolution", size.width, size.height)
                shader.setFloatUniform("density", density)
                shader.setFloatUniform("outset", outset)
                shader.setFloatUniform("tint", tint.red, tint.green, tint.blue)
                val bounds = lens?.bounds?.invoke()
                if (bounds != null) {
                    shader.setFloatUniform("lens", bounds.left + outset, bounds.top + outset, bounds.right + outset, bounds.bottom + outset)
                } else {
                    shader.setFloatUniform("lens", 0f, 0f, 0f, 0f)
                }
                shader.setFloatUniform("press", lens?.press?.invoke() ?: 0f)
                renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "backdrop").asComposeRenderEffect()
            }
            .hazeEffect(state = hazeState) {
                blurRadius = 8.dp
                backgroundColor = GlassSurfaceColor
                tints = listOf(HazeTint(Color.Transparent))
                noiseFactor = 0f
            },
    )
}

internal actual fun pillGlassRefracts(hazeState: HazeState?): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && hazeState?.blurEnabled == true
