# Aarso rendering handoff — for refinement in a conversational Claude chat

> **Why this file exists.** Tight visual/material iteration (AGSL shaders, the Aeon
> atoms, the Lens feel) is faster in a back-and-forth Claude chat with a quick
> WebGL/preview loop than in the build-repo, where every change is a 10-minute
> native cross-compile + a device install. This doc is **self-contained**: paste it
> into a fresh chat and it has the full context to refine the primitives below
> without the repo. When a primitive is good, port it back to the file named under
> it and rebuild here. Nothing in this doc needs repo access to work on.

## The material argument — the physics *is* the sovereignty thesis

A generic chat app is **flat**: opaque rectangles that announce their state in words
("Reading…", "Loading…", a spinner). You are *told* what is happening by software that
sits between you and the truth. Aarso refuses this. Its surfaces are **materials with
real physical behaviour**, and that behaviour *is* the information — you are not told,
you **see**. This is the legibility thesis made literal, and it is the same move as
cognitive sovereignty: the interface does not mediate reality through a language it
controls; it presents material whose physics you read for yourself. Every principle
below is downstream of one law.

> **THE LAW — state is *shown* by material, never *said* by language.**
> A surface conveys what it is doing through how its material behaves — focus,
> refraction, surface tension, phase change — and **never** through a status word or a
> spinner. This is the exact bug the owner caught in the Lens: "Reading…" was the app
> *telling* you; the lens now *defocuses and refocuses* to *show* you. Generalise it:
> **if you reach for a word to describe a state, you have failed the thesis** — find the
> material behaviour that shows it. Words carry *content* (a plain reading, a label);
> never *status*.

### Treatment of materials (the physics budget)
Materials are **lit, not faked**. What a refiner must keep:
- **Real light, one world.** Surfaces use actual lighting math — hemisphere normals,
  Fresnel rim, a tight specular, an environment-reflection gradient (see the bead, §A) —
  under **one consistent key light** (upper-left). A gloss must be a *reflection* that
  reads like one, not a flat top→bottom gradient standing in for lighting.
- **Refraction over transparency.** Glass does not merely lower opacity; it **bends the
  layer behind it** (§B's room). Seeing-through is an *active optical act* — which is the
  whole point: legibility is something the material *does*, not a passive alpha.
- **Inky, low-key, dithered.** Everything lives in near-black; light is scarce and spent
  to reveal form. Continuous tone (dither the output) — never posterised bands. A calm
  dark field *is* attention sovereignty: nothing shouts, so the eye chooses.
- **Weight, not bounce.** Motion settles on `cubic-bezier(.4,0,.2,1)`
  (`FastOutSlowInEasing`), ~300–320 ms, **no spring**. The materials are liquid and
  deliberate, never the dopamine-bounce of consumer UI. Calm is a sovereignty signal.

### Layers (depth carries meaning, never ornament)
Aarso is built from **stacked physical layers**, and depth encodes structure:
- **A world sits behind the glass.** The dot-grid *room* is the substrate; refraction
  proves the layer behind is real. Materials are in front of a context, not floating.
- **The Lens stack:** code (sharp, syntax-lit) → glass (the meaning, formed *on its
  surface*) → the watched badge. The glass sits *above* the code and *transforms* it, so
  you see both the input and the act of interpretation at once.
- **Selection is a layer, not a colour.** A chosen log row is a backing layer the white
  bullet **floats in front of** (balanced L/R margins) — depth marks "here", calmly.
- **The z-axis is real.** The Tree lives on z (pinch to zoom in/out); front/back is the
  app's structural axis. The material layering on every surface rehearses that same logic.

### Behaviors (materials respond — to time, touch, and state)
A material is alive; it answers three things:
- **State → optics.** The Lens *focuses* as meaning settles, and holds the last reading
  blurred while the next resolves (re-focusing, never blanking).
- **Touch → surface tension.** Ferrofluid answers a press by growing a spike-crown and
  relaxing — the control is *liquid under your finger*, tracking **1:1** (no inertia you
  didn't impart; you are in command of the material, not the reverse).
- **Time → slow life.** Even at rest a sheen sweeps; the glass→sand wave is a phase change
  over time. Quiet continuous motion says the surface is real, not a screenshot.
- **The signature:** every response is calm and weighty — no spring, no flash-to-announce.
  The behaviour itself is the sovereignty posture.

### Composition (one coherent physics; materials assigned by meaning)
The whole interface is composed from a **tiny constant set** — one key light, one slant
(0.2), one violet ramp from one seed (`#8E7BFF`), one radius language (rx6 / rx≈3.5), one
easing. That economy is itself the argument that this is a *designed physics*, not a
theme. Materials are **assigned by meaning**, not taste:
- **Glass = legibility / seeing-through.** Used wherever the act is *understanding* (the
  Lens). Its physics — focus, refraction — is the cognitive act made visible.
- **Ferrofluid = the live control / action.** The touchable, reflective, surface-tension
  body — buttons and the things you *act through*. (Owner's target: this material on a
  **defined** rounded silhouette — see the button ask below — not loose droplets.)
- **Dot-grid room = substrate / context.** The world your work sits in.
- **Sheen / gloss = a reflection layer**, never a flat highlight — it asserts a light and
  a wet surface catching it.
- **Watched (cloud) carries a visible material marker.** The sovereignty distinction is
  *composed in*: on-device is the default material; a cloud touch is a surface you can
  **see** is foreign (the watched badge), never a hidden pane. Composition is where
  "every cloud touch is visible" stops being a rule and becomes something you can point at.

**Glass → sand is a phase change, and the owner holds its canonical meaning.** The pane
*un-forms* into granular silica top-to-bottom — a fused, rigid, single sheet releasing
back into its own free grains. The natural reading in this language is **de-fusing: a
fixed/foreign interface releasing back into raw material that is yours, granular and
uncommitted** — but pin the precise meaning with the owner. A refiner should **preserve
the transformation and ask**, never swap it for a generic "shatter": *cracked* reads as
breakage/failure, a phase change to *sand* reads as **release** (which is why the owner's
"looks cracked, not like sand" was a thesis error, not just a visual one).

### Non-negotiable constants (quick reference)
- Palette fixed (§C) — derive, never invent, new colours; keep WCAG AA.
- One slant everywhere: **slope 0.2** (≈11°). Button radius **rx 6**. Count-chip **rx ≈ 3.5**.
- One key light (upper-left); gloss is reflection, output is dithered.
- Settle on `cubic-bezier(.4,0,.2,1)`, ~300 ms, **no spring**.
- No status words — ever (THE LAW).

## The button direction (the live ask, June 2026)

The owner's words, verbatim, so a refiner doesn't overshoot:

> "They would be **ferrofluid**, but I want them to have some sort of
> **shape/definition, not just droplets**. I just meant **candy-like in terms of
> glossiness, or rounded edges** — they look like sharp parallelograms right now."

So the target for action surfaces (buttons, the back "shoulder", chips):

- **Material = ferrofluid** (inky, reflective, surface-tension, a slight live quality)
  — the existing bead shader in §A is the material reference.
- **…but with definition**: it is still a **bar/pill with a clear silhouette**, not a
  blob or a cluster of droplets. The ferrofluid skins a *defined button shape*.
- **Glossy + rounded** ("candy" only meant this): a wet specular highlight and
  **rounded corners**, never sharp points. The sharp parallelogram chips are the
  thing to fix first (now rounded to rx≈3.5 in §D — push it further if needed).
- Likely end state: a rounded-rect / rounded-parallelogram **button whose fill is
  the ferrofluid material** (inky body + environment reflection + fresnel rim +
  hot specular), maybe a faint surface-tension wobble on press — but the rectangle
  stays a legible rectangle. That AGSL button does **not** exist yet; it's the prize.

---

## How rendering works here (so ported code drops straight in)

- **UI = Jetpack Compose** (Kotlin). **Material effects = AGSL** (Android Graphics
  Shading Language — GLSL-like) via `android.graphics.RuntimeShader`, drawn through a
  `ShaderBrush` in a `Modifier.drawWithCache { … onDrawBehind { drawRect(brush) } }`.
  AGSL needs **Android 13+ (API 33)**; the probe guards and shows a message otherwise.
- **AGSL vs GLSL/Shadertoy:** nearly identical math. `half4 main(float2 fragCoord)`
  returns premultiplied colour. Uniforms are set from Kotlin with
  `shader.setFloatUniform("name", …)`. To refine in a browser, port the shader body
  to a Shadertoy/WebGL `mainImage` (these shaders were originally tuned that way):
  `iResolution`/`iTime` map directly; replace `float2`→`vec2`, `half4`→`vec4`.
- **Animation time** comes from a Compose `rememberInfiniteTransition` driving a
  `float` 0→period, fed to the `iTime` uniform.
- **The Hyle Probe harness.** `:hyle-probe` is a standalone debug app
  (`HyleProbeActivity`) with a bottom `TabRow` + `HorizontalPager`; each page is one
  probe composable. Today's tabs: *Radiant glow*, *Glass + sand*, *Ferrofluid bead*,
  *Lens*, *Atoms*. To add a probe: write `fun XProbe()` and add it to the `tabs`
  list + the `when(page)`. This is the on-device feel surface; nothing here ships in
  the main app until verified.
- **Build / run:**
  - Probe APK: `./gradlew :hyle-probe:assembleDebug` →
    `hyle-probe/build/outputs/apk/debug/hyle-probe-debug.apk`.
  - Full app: `./gradlew :app:assembleFullDebug`.
  - Delivery: APKs are pushed to the orphan branch **`apk-dist`** (chat upload caps
    ~30 MB; the full app is ~64 MB). Install from
    `https://github.com/<owner>/<repo>/raw/apk-dist/<file>.apk`.
  - **No emulator/device in CI** — all feel is owner-verified on the phone.

---

## The primitives

### A. Ferrofluid bead (AGSL) — the *material reference* for buttons

**File:** `hyle-probe/src/main/java/dev/aarso/hyleprobe/FerrofluidProbe.kt`
**Intent:** an inky, mirror-bright liquid bead: 3D-hemisphere specular (round
highlight, not a wedge), cool fresnel rim, an 8-spike crown that grows on press.
**Uniforms:** `iResolution`, `iCenter`, `iRadius`, `iSpikeAmp` (0 rest → 0.22 press),
`iTime`. **Open refinement:** this is a *bead*; the prize is to keep this material
but wrap it on a **defined button silhouette** (see the button ask above) — i.e.
make the SDF a rounded-rect/parallelogram instead of a circle+spikes, keep the
body/fresnel/spec lighting, add a gentle surface-tension press wobble.

```glsl
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
    return half4(half3(col * a), half(a));           // premultiplied
}
```

Kotlin driver (press envelope): one cycle / 2 s `sin²`, then rest 2 s;
`spikeAmp = if (t < 2f) 0.22f * env else 0f`; `iRadius = min(w,h)/2 * 0.78` to leave
spike headroom.

### B. Glass → sand (AGSL) — material transformation

**File:** `hyle-probe/src/main/java/dev/aarso/hyleprobe/GlassSandProbe.kt`
**Intent:** a refractive glass pane over a dot-grid "room"; a top→bottom wave
**un-forms** the glass into **granular dark silica**. **Owner feedback (addressed,
verify on device):** it read as *cracked glass* because the silica was shaded by
darkening Worley **cell borders** (F2−F1) — literal crack lines. It's now shaded as
**rounded lit grains** (F1 bump) with only a *soft* contact shadow + finer cells +
fine speckle. **Open refinement (best done in WebGL):** push grain feel — tune
`grains(sp * 42.0)` frequency, `grainBump` smoothstep range, `speck` at `sp*95`,
`silLo/silHi`; consider a second finer grain octave for "packed dune". Keep the
refraction/ridge/zoom intact.

```glsl
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
    float2 i = floor(x); float2 f = fract(x);
    float2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    float a = hash(i), b = hash(i + float2(1.0, 0.0));
    float c = hash(i + float2(0.0, 1.0)), d = hash(i + float2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}
float fbm(float2 x) {
    float s = 0.0, a = 0.5;
    for (int k = 0; k < 3; k++) { s += a * vnoise(x); x = x * 2.0 + float2(19.1, 7.3); a *= 0.5; }
    return s;
}

// Worley grains: (F1, F2, id). F1 shades each cell as a rounded lit particle;
// F2-F1 gives only a SOFT contact shadow between grains — packed sand, not cracks.
float3 grains(float2 p) {
    float2 ip = floor(p); float2 fp = fract(p);
    float f1 = 9.0, f2 = 9.0, id = 0.0;
    for (int j = -1; j <= 1; j++) for (int i = -1; i <= 1; i++) {
        float2 g = float2(float(i), float(j));
        float2 o = hash2(ip + g);
        float2 r = g + o - fp;
        float dd = dot(r, r);
        if (dd < f1) { f2 = f1; f1 = dd; id = hash(ip + g); }
        else if (dd < f2) { f2 = dd; }
    }
    return float3(sqrt(f1), sqrt(f2), id);
}

float3 room(float2 r) {
    float3 base = mix(float3(0.028, 0.030, 0.038), float3(0.05, 0.052, 0.064), smoothstep(0.7, -0.5, r.y));
    float2 g = r * 7.0; float2 gi = fract(g) - 0.5; float d = length(gi);
    base += float3(0.055, 0.07, 0.105) * smoothstep(0.40, 0.25, d) * 0.55;
    return base;
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / iResolution;
    float aspect = iResolution.x / iResolution.y;
    float2 p = (uv - 0.5); p.x *= aspect;

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
    paneHalf.x += 0.05 * band0;                                   // side bulge at the ridge
    float corner = 0.03;
    float2 dd = abs(w) - paneHalf + float2(corner);
    float rrect = min(max(dd.x, dd.y), 0.0) + length(max(dd, float2(0.0))) - corner;
    float onPane = smoothstep(0.004, -0.004, rrect);
    float2 puv = w / (2.0 * paneHalf) + 0.5;

    float2 bgCoord = p;
    float3 bg = room(bgCoord);

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

    // ---- silica = rounded lit grains (only inside the band, for speed) ----
    float pq = (frontY - puv.y) / 0.22;
    float passed = exp(-pq * pq) * smoothstep(0.0, 0.05, frontY - puv.y);
    float sandAmt = clamp(smoothstep(0.0, 0.45, band) + 0.5 * passed, 0.0, 1.0);
    float3 sandCol = float3(0.0);
    if (sandAmt > 0.001) {
        float2 sp = float2(puv.x * aspect, puv.y);
        float3 gr = grains(sp * 42.0);                            // finer cells = smaller grains
        float grainBump = smoothstep(0.95, 0.05, gr.x);           // rounded particle: lit centre, soft edge
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
    col += float3((hash(fragCoord) - 0.5) / 255.0);              // dither, no banding
    return half4(half3(col), 1.0);
}
```

### C. Aeon palette + spec constants (locked)

**File:** `app/src/main/java/dev/aarso/ui/theme/AeonColors.kt` (runtime-swappable in
the app); mirrored as constants in the probe. The violet ramp derives from the one
Aarso violet `#8E7BFF`. Dark baseline:

| token | hex | use |
|---|---|---|
| ink | `#0E0F12` | base surface |
| raised | `#16181D` | raised card |
| inset | `#20242B` | input / recessed |
| outline | `#262A31` | divider/border |
| hairline | `#24ECEDEF` (14% white) | crisp 1dp edges |
| textHigh | `#ECEDEF` | primary text |
| textMid | `#9CA3AF` | secondary |
| textDisabled | `#4A4E57` | disabled |
| violet | `#8E7BFF` | accent |
| violetPressed | `#7262CC` | pressed accent |
| violetDim | `#2A2541` | selection tint |
| onViolet | `#160F2E` | text on violet |
| success/warning/error | `#3AB700` / `#F78819` / `#EE322C` | fit / watched / error |

**Constants:** `SLANT = 0.2` (every slanted edge), button `rx = 6dp`, count-chip
`rx ≈ 3.5dp`. Contrast must stay WCAG AA (text ≥ 4.5:1, UI ≥ 3:1).

### D. Slanted **rounded** shapes — the polygon primitive

**File:** `hyle-probe/.../AeonAtomsProbe.kt` (the canonical version of this primitive).
The fix for "sharp parallelograms": round each corner by pulling back `r` along both
edges and sweeping a quadratic through the original vertex. One helper drives the
parallelogram chip *and* the candy/shoulder trapezoid.

```kotlin
private fun Offset.unit(): Offset { val l = getDistance(); return if (l == 0f) this else this / l }

private fun Path.roundedPolygon(pts: List<Offset>, r: Float) {
    for (i in pts.indices) {
        val curr = pts[i]
        val prev = pts[(i + pts.size - 1) % pts.size]
        val next = pts[(i + 1) % pts.size]
        val enter = curr + (prev - curr).unit() * r
        val exit  = curr + (next - curr).unit() * r
        if (i == 0) moveTo(enter.x, enter.y) else lineTo(enter.x, enter.y)
        quadraticBezierTo(curr.x, curr.y, exit.x, exit.y)   // round through the vertex
    }
    close()
}
// Parallelogram vertices: (s,0) (w,0) (w-s,h) (0,h),  s = h * SLANT
// Candy trapezoid vertices: (0,0) (w-s,0) (w,h) (0,h)
// Wrap each in a Shape: Outline.Generic(Path().apply { roundedPolygon(pts, r) }).
```

**Open refinement:** the corner radius (`CORNER_DP = 3.5`) and whether the
parallelogram slant should also apply to selection chips (currently only count
chips + the breadcrumb separator are slanted). Pitfall worth noting: a top-level
`val Outline = Color(…)` will **shadow** `androidx.compose.ui.graphics.Outline` —
name palette colours so they don't collide with Compose types.

### E. Buttons — flat (today) vs glossy (direction)

**File:** `hyle-probe/.../AeonAtomsProbe.kt`. Flat primary = solid violet, `rx 6`.
The glossy ("candy") direction = a vertical body gradient + a **specular top sheen**
(bright at the very top, gone by ~45%), same footprint. This is the *cheap* gloss;
the *real* target is the ferrofluid-material button (§A). Gloss recipe:

```kotlin
// body
Brush.verticalGradient(listOf(Color(0xFFA493FF), Violet, VioletPressed))   // primary
// sheen overlay (matchParentSize, same clip)
Brush.verticalGradient(0f to Color(0x40FFFFFF), 0.45f to Color(0x00FFFFFF), 1f to Color(0x00FFFFFF))
```

### F. Smaller atoms (in `AeonAtomsProbe.kt`)

- **Selection chip:** today `rx 6` rounded rect (violetDim fill + violet border when
  selected). Open: should it take the 0.2 slant too?
- **Count chip:** rounded parallelogram (§D), `violetDim` + dim violet border,
  shows a number — used inline and under an active tab.
- **Slant separator:** a "/" drawn as a single line at slope 0.2 between breadcrumbs
  (`drawLine` with the same shear).
- **Tab indicator ON the divider:** a 2dp violet bar drawn at the selected tab's
  x-offset, **overlapping** the 1dp `outline` divider (not below it), spanning the
  full tab width incl. the count chip under the label.
- **Log entry backing layer:** the selected run/log row gets an `inset`-filled,
  6dp-rounded backing **inset 4dp from both edges** so the white bullet (`•`) floats
  *in front of* it with balanced L/R margins.

### G. The Lens — legibility through *focus*, not a status word

**Files:** `app/src/main/java/dev/aarso/ui/codelens/CodeLensScreen.kt` (real, model-
driven) + `hyle-probe/.../LensProbe.kt` (canned feel). **Intent:** a draggable
"smart glass" you pass over a file; the lines under it are replaced **on the glass**
by a plain-English reading. **The thesis fix (done):** it must never print
"Reading…". Instead the **meaning is always on the glass**, and the *material* shows
whether it's legible yet:

- while the lens moves / the model reads → the reading is **out of focus**
  (`Modifier.blur`, animated ~11.dp), dimmed slightly;
- when it **settles** → the lens **focuses**, blur → 0, the meaning sharpens.
- The real screen keeps the **last** reading on the glass, blurred, while the next
  resolves (re-focusing, not blanking); on the very first read the covered code
  shows through, out of focus, until the meaning lands.

```kotlin
val focusBlur by animateDpAsState(
    targetValue = if (settled) 0.dp else 11.dp,
    animationSpec = tween(if (settled) 300 else 90, easing = FastOutSlowInEasing),
    label = "lensFocus",
)
// reading Text: Modifier.padding(top = 6.dp).blur(focusBlur)   // never a status word
```

Also in the Lens: a **local** syntax highlighter (no model, no network) — a small
`TOKEN` regex + `KEYWORDS` set → `AnnotatedString` spans for keywords/strings/
numbers/comments. Glass fill ≈ 95% opaque dark violet; a **watched** badge appears
when the interpreter is a cloud provider (binding rule: every cloud touch is visible).
**Open refinement:** whether to push the optics further (a real refraction of the
code through the glass as it focuses), and the glass tint/opacity.

---

## Round-trip checklist (refine in chat → port back here)

| Primitive | Port back into | Then |
|---|---|---|
| Ferrofluid material / new AGSL button | `hyle-probe/.../FerrofluidProbe.kt` (or a new `*Probe.kt`) | add a tab; `:hyle-probe:assembleDebug` |
| Glass→sand tuning | `GlassSandProbe.kt` (the `GLASS_SAND_AGSL` string) | rebuild probe |
| Rounded slant shapes / atoms | `AeonAtomsProbe.kt` | rebuild probe; later lift into `app/.../ui/aeon/Aeon.kt` |
| Lens optics | `LensProbe.kt` + `app/.../ui/codelens/CodeLensScreen.kt` | rebuild probe + `:app:assembleFullDebug` |

**Validate after porting:** `./gradlew :app:testFullDebugUnitTest
:app:testPlayDebugUnitTest` (45+ JVM tests must stay green — they cover domain logic,
not rendering, but the app must compile), then assemble + push to `apk-dist`.

**AGSL gotchas when porting from WebGL:** AGSL has no implicit `vec`/`mat` aliases
(`float2`/`half4`); loops must have constant bounds; `pow(x,y)` needs `x ≥ 0`;
the shader is validated **only at runtime on device** — a typo compiles in Gradle
and fails on the phone, so the probe wraps `RuntimeShader(src)` in `runCatching` and
shows a message rather than crashing. Keep that guard.

## What must not regress (binding rules, condensed)

No telemetry/analytics, ever. On-device is the default; every cloud touch is a
visible **watched** object. Keys live in the Android Keystore, sent only to their own
host. The multi-model loop is a **council**, never "MoE". These are app-level, but a
refiner touching the Lens/Settings surfaces should not add a network call or a hidden
cloud path in the name of a nicer render.
