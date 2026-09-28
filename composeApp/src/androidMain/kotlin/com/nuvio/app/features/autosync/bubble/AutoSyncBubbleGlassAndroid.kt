package com.nuvio.app.features.autosync.bubble

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope

// The pill's liquid glass (features/pillnav PillGlassShader), sized for the bubble and fed the
// copied video instead of a Haze backdrop, so the bubble needs no blur layer of its own:
// - the whole bubble is a lens: the video is pulled inward along the edge normal with a circular
//   profile, so it magnifies towards the rim like a thick glass edge;
// - faint, ordered dispersion (red bends least, blue most) only near the rim;
// - a light vibrancy boost, and four soft taps in place of a blur;
// - the glass darkens slightly towards its lower rim.
// The frost, tint and rim highlights on top stay the bubble's own drawing.
private const val BubbleGlassShader = """
uniform shader backdrop;
uniform float2 resolution;
uniform float density;
uniform float corner;
uniform float2 origin;
uniform float2 scale;

float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}

// Signed distance to a rounded box and its outward normal, packed as (distance, normal.x, normal.y).
float3 roundedBox(float2 local, float2 halfSize, float radius) {
    float2 q = abs(local) - halfSize + radius;
    float2 outside = max(q, 0.0);
    float d = length(outside) + min(max(q.x, q.y), 0.0) - radius;
    float2 n;
    if (q.x > 0.0 && q.y > 0.0) {
        n = outside / max(length(outside), 0.001);
    } else if (q.x > q.y) {
        n = float2(1.0, 0.0);
    } else {
        n = float2(0.0, 1.0);
    }
    n *= float2(local.x < 0.0 ? -1.0 : 1.0, local.y < 0.0 ? -1.0 : 1.0);
    return float3(d, n);
}

half3 soft(float2 position) {
    float r = 2.0 * density;
    float2 p = (position - origin) * scale;
    float2 o = float2(r, r) * scale;
    return (backdrop.eval(p + o).rgb + backdrop.eval(p - o).rgb +
        backdrop.eval(p + float2(o.x, -o.y)).rgb + backdrop.eval(p + float2(-o.x, o.y)).rgb) * 0.25;
}

half4 main(float2 position) {
    float2 halfSize = resolution * 0.5;
    float3 box = roundedBox(position - halfSize, halfSize, min(corner, min(halfSize.x, halfSize.y)));
    float sd = min(box.x, 0.0);
    float2 normal = box.yz;

    // 0 deep inside, 1 at the rim.
    float edge = clamp(1.0 + sd / (14.0 * density), 0.0, 1.0);
    float bend = circleMap(edge) * 10.0 * density;
    float2 samplePos = position - normal * bend;
    float2 spread = normal * bend * 0.07 * edge;

    half3 color = half3(
        soft(samplePos + spread).r,
        soft(samplePos).g,
        soft(samplePos - spread).b
    );
    half luminance = dot(color, half3(0.2126, 0.7152, 0.0722));
    color = clamp(mix(half3(luminance), color, 1.3), 0.0, 1.0);
    color *= 1.0 - 0.14 * pow(edge, 3.0) * max(normal.y, 0.0);
    return half4(color, 1.0);
}
"""

@Composable
internal fun rememberBubbleGlassPainter(): BubbleBackdropPainter? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) remember { BubbleGlassPainter() } else null

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class BubbleGlassPainter : BubbleBackdropPainter {
    private val shader = RuntimeShader(BubbleGlassShader)
    private val brush = ShaderBrush(shader)

    // The sampler alternates between two bitmaps; keep a shader for each instead of making one per copy.
    private val bitmaps = arrayOfNulls<Bitmap>(2)
    private val bitmapShaders = arrayOfNulls<BitmapShader>(2)
    private var nextSlot = 0

    private fun shaderFor(bitmap: Bitmap): BitmapShader {
        for (i in bitmaps.indices) {
            if (bitmaps[i] === bitmap) return bitmapShaders[i]!!
        }
        val created = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            filterMode = BitmapShader.FILTER_MODE_LINEAR
        }
        bitmaps[nextSlot] = bitmap
        bitmapShaders[nextSlot] = created
        nextSlot = 1 - nextSlot
        return created
    }

    override fun DrawScope.paint(frame: BubbleBackdropFrame, bubble: Rect, corner: Float) {
        val image = frame.image.asAndroidBitmap()
        val copied = frame.windowRect
        if (copied.width <= 0f || copied.height <= 0f) return
        shader.setInputShader("backdrop", shaderFor(image))
        shader.setFloatUniform("resolution", size.width, size.height)
        shader.setFloatUniform("density", density)
        shader.setFloatUniform("corner", corner)
        shader.setFloatUniform("origin", copied.left - bubble.left, copied.top - bubble.top)
        shader.setFloatUniform("scale", image.width / copied.width, image.height / copied.height)
        drawRect(brush)
    }
}
