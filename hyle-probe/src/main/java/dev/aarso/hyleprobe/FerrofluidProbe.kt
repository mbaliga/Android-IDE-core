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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// AGSL: an inky mirror-bright ferrofluid bead. A proper 3D hemisphere normal gives a
// round specular highlight (the earlier 2D-radial normal produced a hard white wedge),
// a cool fresnel rim reads as a reflective-liquid edge, and an 8-spike crown grows
// under the (auto-animated) press. iSpikeAmp: 0 = rest, 0.22 = full press. Android 13+.
private const val FERROFLUID_AGSL = """
uniform float2 iResolution;
uniform float2 iCenter;
uniform float  iRadius;
uniform float  iSpikeAmp;
uniform float  iTime;

half4 main(float2 fragCoord) {
    float2 dir = (fragCoord - iCenter) / iRadius;
    float dist = length(dir);
    float angle = atan(dir.y, dir.x);

    float spike = sin(angle * 8.0) * 0.5 + 0.5;
    float edge = 1.0 + spike * iSpikeAmp;
    float sdf = dist - edge;                          // < 0 inside the bead

    // 3D hemisphere normal -> a round highlight, not a wedge.
    float zz = sqrt(max(0.0, edge * edge - dist * dist));
    float3 n = normalize(float3(dir, zz));
    float ndc = clamp(zz / max(edge, 0.001), 0.0, 1.0);   // 1 at center -> 0 at rim

    // Inky body with a vertical environment-reflection gradient (top reflects lighter).
    float envv = clamp(0.5 + n.y * 0.5, 0.0, 1.0);
    float3 body = mix(float3(0.010, 0.013, 0.020), float3(0.10, 0.13, 0.17), envv * envv);
    // Cool fresnel rim — the reflective-liquid edge.
    float fres = pow(1.0 - ndc, 2.2);
    body += float3(0.22, 0.24, 0.30) * fres;
    // Broad soft sheen (the 'wet' sky reflection) from upper-left.
    float sheen = max(0.0, dot(n, normalize(float3(-0.3, 0.7, 0.45))));
    body += float3(0.06, 0.07, 0.09) * pow(sheen, 2.0);
    // Tight hot specular dot.
    float3 L = normalize(float3(-0.45, 0.6, 0.7));
    float3 H = normalize(L + float3(0.0, 0.0, 1.0));
    float spec = pow(max(0.0, dot(n, H)), 120.0) * 1.3;

    float3 col = body + float3(spec);
    float a = smoothstep(0.02, -0.02, sdf);          // anti-aliased edge
    return half4(half3(col * a), half(a));           // premultiplied; sits on the dark body
}
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun FerrofluidBead(modifier: Modifier = Modifier) {
    val shader = remember { runCatching { RuntimeShader(FERROFLUID_AGSL) }.getOrNull() }
    if (shader == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("ferrofluid shader didn't compile on this device", color = Color(0xFF9AA1AD), fontSize = 13.sp)
        }
        return
    }
    val brush = remember(shader) { ShaderBrush(shader) }

    val cycleSecs = 4
    val transition = rememberInfiniteTransition(label = "ferroTime")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = cycleSecs.toFloat(),
        animationSpec = infiniteRepeatable(tween(cycleSecs * 1000, easing = LinearEasing)),
        label = "t",
    )

    Box(
        modifier.drawWithCache {
            shader.setFloatUniform("iResolution", size.width, size.height)
            shader.setFloatUniform("iCenter", size.width / 2f, size.height / 2f)
            // Leave headroom for the spikes (edge reaches ~1.22x) inside the box.
            val radius = minOf(size.width, size.height) / 2f * 0.78f
            shader.setFloatUniform("iRadius", radius)
            onDrawBehind {
                // Smooth press: one cycle per 2 s (sin^2 envelope), then rest 2 s.
                val phase = Math.PI.toFloat() * t / 2f
                val env = Math.sin(phase.toDouble()).toFloat().let { s -> s * s }
                val spikeAmp = if (t < 2f) 0.22f * env else 0f
                shader.setFloatUniform("iSpikeAmp", spikeAmp)
                shader.setFloatUniform("iTime", t)
                drawRect(brush)
            }
        },
    )
}

@Composable
fun FerrofluidProbe() {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF08090C))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Hyle · ferrofluid bead", color = Color(0xFFEDEFF3), fontSize = 20.sp)
        Text(
            "reflective — press cycle auto-animated",
            color = Color(0xFF9AA1AD),
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 28.dp),
        )
        Box(Modifier.size(240.dp)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                FerrofluidBead(Modifier.fillMaxSize())
            } else {
                Text(
                    "AGSL needs Android 13+",
                    color = Color(0xFF9AA1AD),
                    fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}
