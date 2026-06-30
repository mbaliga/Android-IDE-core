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

// AGSL: a refractive glass pane over a dot-grid "room", with a top→bottom wave that
// un-forms the glass into GRANULAR dark silica — packed Worley cells shaded as rounded
// LIT grains with only a soft contact shadow between them (no hard cracks) behind a noisy
// wave front. The ridge bulges the pane's sides (parallax swell). An 8 s loop zooms
// out first so the pane reads as a bounded object. Low-freq quintic noise + dither =
// no pixelation; pow() never takes a negative base; the costly grain field is computed
// only inside the band (a branch) to keep the zoom smooth. Tuned in a WebGL port.
private const val GLASS_SAND_AGSL = """
uniform float2 iResolution;
uniform float  iTime;

float hash(float2 p) {
    p = fract(p * float2(443.897, 441.423));
    float dp = dot(p, float2(p.y, p.x) + float2(19.19));
    p += float2(dp);
    return fract((p.x + p.y) * p.x);
}

float2 hash2(float2 p) { return float2(hash(p), hash(p + float2(37.2, 17.9))); }

float vnoise(float2 x) {
    float2 i = floor(x);
    float2 f = fract(x);
    float2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    float a = hash(i);
    float b = hash(i + float2(1.0, 0.0));
    float c = hash(i + float2(0.0, 1.0));
    float d = hash(i + float2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(float2 x) {
    float s = 0.0;
    float a = 0.5;
    for (int k = 0; k < 3; k++) {
        s += a * vnoise(x);
        x = x * 2.0 + float2(19.1, 7.3);
        a *= 0.5;
    }
    return s;
}

// Worley grains: (F1, F2, per-grain id). F1 shades each cell as a rounded lit particle;
// F2-F1 gives only a SOFT contact shadow between grains — packed sand, not a cracked pane.
float3 grains(float2 p) {
    float2 ip = floor(p);
    float2 fp = fract(p);
    float f1 = 9.0;
    float f2 = 9.0;
    float id = 0.0;
    for (int j = -1; j <= 1; j++) {
        for (int i = -1; i <= 1; i++) {
            float2 g = float2(float(i), float(j));
            float2 o = hash2(ip + g);
            float2 r = g + o - fp;
            float dd = dot(r, r);
            if (dd < f1) { f2 = f1; f1 = dd; id = hash(ip + g); }
            else if (dd < f2) { f2 = dd; }
        }
    }
    return float3(sqrt(f1), sqrt(f2), id);
}

// the room behind the glass — a soft dot grid + gradient, so refraction is visible.
float3 room(float2 r) {
    float3 base = mix(float3(0.028, 0.030, 0.038), float3(0.05, 0.052, 0.064), smoothstep(0.7, -0.5, r.y));
    float2 g = r * 7.0;
    float2 gi = fract(g) - 0.5;
    float d = length(gi);
    base += float3(0.055, 0.07, 0.105) * smoothstep(0.40, 0.25, d) * 0.55;
    return base;
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / iResolution;
    float aspect = iResolution.x / iResolution.y;
    float2 p = (uv - 0.5);
    p.x *= aspect;

    float T = mod(iTime, 8.0);
    float zo = smoothstep(0.0, 0.7, T) - smoothstep(7.3, 8.0, T);
    float paneScale = mix(1.10, 0.78, zo);
    float2 w = p / paneScale;

    float frontY = mix(-0.25, 1.25, clamp((T - 1.6) / 3.6, 0.0, 1.0));
    float wband = 0.11;
    float halfY = 0.5 * 0.985;
    float puvy0 = w.y / (2.0 * halfY) + 0.5;
    float band0 = exp(-((puvy0 - frontY) * (puvy0 - frontY)) / (2.0 * wband * wband));

    float2 paneHalf = float2(0.5 * aspect, 0.5) * 0.985;
    paneHalf.x += 0.05 * band0;                                   // SIDE BULGE at the ridge
    float corner = 0.03;
    float2 dd = abs(w) - paneHalf + float2(corner);
    float rrect = min(max(dd.x, dd.y), 0.0) + length(max(dd, float2(0.0))) - corner;
    float onPane = smoothstep(0.004, -0.004, rrect);
    float2 puv = w / (2.0 * paneHalf) + 0.5;

    float2 bgCoord = p;
    float3 bg = room(bgCoord);

    // fractured (jagged) wave front, not a smooth melt
    float edgeJag = (fbm(float2(puv.x * 9.0, T * 0.3)) - 0.5) * 0.05;
    float dy = puv.y - frontY + edgeJag;
    float band = exp(-(dy * dy) / (2.0 * wband * wband));
    float ridgeGrad = -(dy / (wband * wband)) * band;

    // ---- glass refracts the room ----
    float bev = smoothstep(0.0, 0.10, -rrect);
    float2 outward = normalize(w + float2(0.0001));
    float2 refr = outward * (1.0 - bev) * 0.06 + float2(0.0, ridgeGrad * 0.05) + p * band * 0.12 - p * 0.03;
    float3 refracted = float3(
        room(bgCoord + refr * 0.96).x,
        room(bgCoord + refr * 1.03).y,
        room(bgCoord + refr * 1.10).z);
    float sheen = pow(0.5 + 0.5 * sin((puv.x * 1.3 + puv.y * 0.5) * 3.1415927 - T * 0.5), 2.0);
    float3 glassCol = refracted * 1.06 + float3(0.04, 0.05, 0.07) * sheen;
    float rim = smoothstep(0.014, 0.0, abs(rrect)) * onPane;
    glassCol += float3(0.10, 0.12, 0.16) * rim;

    // ---- silica = fragmented grains (computed only inside the band, for speed) ----
    float pq = (frontY - puv.y) / 0.22;
    float passed = exp(-pq * pq) * smoothstep(0.0, 0.05, frontY - puv.y);
    float sandAmt = clamp(smoothstep(0.0, 0.45, band) + 0.5 * passed, 0.0, 1.0);
    float3 sandCol = float3(0.0);
    if (sandAmt > 0.001) {
        float2 sp = float2(puv.x * aspect, puv.y);
        float3 gr = grains(sp * 42.0);                            // finer cells = smaller grains
        float grainBump = smoothstep(0.95, 0.05, gr.x);           // rounded particle: lit centre, soft fall-off
        float contactAO = smoothstep(0.0, 0.18, gr.y - gr.x);     // SOFT contact shadow, not a hard crack
        float tone = gr.z;
        float speck = fbm(sp * 95.0) - 0.5;                       // fine granular speckle
        float grainLum = mix(0.30, 0.86, tone) * (0.55 + 0.45 * grainBump) * (0.82 + 0.18 * contactAO) + speck * 0.13;
        float3 silLo = float3(0.050, 0.052, 0.062);
        float3 silHi = float3(0.30, 0.305, 0.34);
        sandCol = mix(silLo, silHi, clamp(grainLum, 0.0, 1.0));
        sandCol *= 1.0 + 0.22 * clamp(ridgeGrad * wband, -1.0, 1.0);
    }

    float3 surf = mix(glassCol, sandCol, sandAmt);
    surf *= 1.0 - 0.20 * clamp(-ridgeGrad * wband, 0.0, 1.0);     // trailing shadow

    float2 shp = w - float2(0.0, 0.02);
    float2 dssh = abs(shp) - paneHalf + float2(corner);
    float sdv = min(max(dssh.x, dssh.y), 0.0) + length(max(dssh, float2(0.0))) - corner;
    bg = mix(bg, bg * 0.4, smoothstep(0.06, 0.0, sdv) * 0.5);

    float3 col = mix(bg, surf, onPane);
    col += float3((hash(fragCoord) - 0.5) / 255.0);
    return half4(half3(col), 1.0);
}
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun GlassSandEffect(modifier: Modifier = Modifier) {
    // Guard: a shader that fails to compile on this device shows a message instead of
    // crashing the whole probe app (AGSL is validated only at runtime, on device).
    val shader = remember { runCatching { RuntimeShader(GLASS_SAND_AGSL) }.getOrNull() }
    if (shader == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("glass+sand shader didn't compile on this device", color = Color(0xFF9AA1AD), fontSize = 13.sp)
        }
        return
    }
    val brush = remember(shader) { ShaderBrush(shader) }

    val cycleSecs = 8 // matches the in-shader loop period
    val transition = rememberInfiniteTransition(label = "glassSandTime")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = cycleSecs.toFloat(),
        animationSpec = infiniteRepeatable(tween(cycleSecs * 1000, easing = LinearEasing)),
        label = "t",
    )

    Box(
        modifier.drawWithCache {
            shader.setFloatUniform("iResolution", size.width, size.height)
            onDrawBehind {
                shader.setFloatUniform("iTime", t)
                drawRect(brush)
            }
        },
    )
}

@Composable
fun GlassSandProbe() {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E0F12))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Hyle · glass over sand", color = Color(0xFFEDEFF3), fontSize = 20.sp)
        Text(
            "refractive glass over a room; a wave fragments it into silica, top → bottom",
            color = Color(0xFF9AA1AD),
            fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 24.dp),
        )
        Box(Modifier.weight(1f).fillMaxSize()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                GlassSandEffect(Modifier.fillMaxSize())
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
