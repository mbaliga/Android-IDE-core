package dev.aarso.hyleprobe

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aarso.hyle.Pulse
import dev.aarso.hyle.RadiantHues
import kotlin.math.min

// AGSL: a soft radial glow whose intensity breathes on a slow sinusoid (the Pulse
// "heartbeat"). Premultiplied half4 output. Requires Android 13+ (RuntimeShader).
// THROWAWAY PROBE — owner-verified on device; the GPU effect cannot be validated in
// CI (no device). Promotes into :hyle as a real Modifier once it looks right.
private const val RADIANT_AGSL = """
uniform float2 iResolution;
uniform float2 iCenter;
uniform float  iRadius;
uniform float  iTime;
uniform float  iPeriod;
uniform float  iMinA;
uniform float  iMaxA;
uniform float4 iColor;

half4 main(float2 fragCoord) {
    float d = distance(fragCoord, iCenter);
    float f = 1.0 - smoothstep(0.0, iRadius, d);
    f = f * f;
    float phase = sin(iTime * 6.2831853 / iPeriod) * 0.5 + 0.5;
    float amp = mix(iMinA, iMaxA, phase);
    float a = clamp(f * amp * iColor.a, 0.0, 1.0);
    return half4(half3(iColor.rgb * a), half(a));
}
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun RadiantGlow(colorArgb: Long, pulse: Pulse, modifier: Modifier = Modifier) {
    val shader = remember { RuntimeShader(RADIANT_AGSL) }
    val brush = remember { ShaderBrush(shader) }
    val r = ((colorArgb shr 16) and 0xFFL) / 255f
    val g = ((colorArgb shr 8) and 0xFFL) / 255f
    val b = (colorArgb and 0xFFL) / 255f

    val transition = rememberInfiniteTransition(label = "pulse")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = pulse.periodMs / 1000f,
        animationSpec = infiniteRepeatable(tween(pulse.periodMs, easing = LinearEasing)),
        label = "t",
    )

    Box(
        modifier.drawWithCache {
            shader.setFloatUniform("iResolution", size.width, size.height)
            shader.setFloatUniform("iCenter", size.width / 2f, size.height / 2f)
            shader.setFloatUniform("iRadius", min(size.width, size.height) / 2f)
            shader.setFloatUniform("iColor", r, g, b, 1f)
            shader.setFloatUniform("iPeriod", pulse.periodMs / 1000f)
            shader.setFloatUniform("iMinA", pulse.minAlphaPct / 100f)
            shader.setFloatUniform("iMaxA", pulse.maxAlphaPct / 100f)
            onDrawBehind {
                shader.setFloatUniform("iTime", t)
                drawRect(brush)
            }
        },
    )
}

/**
 * Probe screen: the radium radiant glow (breathing) above a static reflective bead, on
 * the dark body — radiant (emits) vs reflective (inert) in one frame. Throwaway dev
 * tool; the effect is owner-verified on device.
 */
@Composable
fun RadiantGlowProbe() {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF08090C))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Hyle · radiant glow", color = Color(0xFFEDEFF3), fontSize = 20.sp)
        Text(
            "radium — breathing ~${Pulse.WATCHED.periodMs / 1000f}s",
            color = Color(0xFF9AA1AD),
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 28.dp),
        )
        Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                RadiantGlow(RadiantHues.RADIUM, Pulse.WATCHED, Modifier.fillMaxSize())
            } else {
                Text("AGSL needs Android 13+", color = Color(0xFF9AA1AD), fontSize = 13.sp)
            }
        }
        // Static reflective control for contrast: inky, specular, does NOT emit.
        Box(
            Modifier
                .padding(top = 28.dp)
                .size(64.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        0f to Color(0xFF566070),
                        0.34f to Color(0xFF222732),
                        1f to Color(0xFF0A0C10),
                        center = Offset(22f, 18f),
                    ),
                ),
        )
        Text(
            "reflective — local, inert (no glow)   ·   #%06X".format(RadiantHues.RADIUM and 0xFFFFFFL),
            color = Color(0xFF5B626E),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}
