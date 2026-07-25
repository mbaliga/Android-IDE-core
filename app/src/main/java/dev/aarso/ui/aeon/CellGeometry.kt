package dev.aarso.ui.hyle

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * Exact cell geometry, transcribed from the owner's Figma exports rather than
 * re-derived from a radius/slant formula — an earlier parametric approximation
 * drifted visibly from the source and was rejected.
 *
 * Two authoring canvases are involved, and the transcription rules differ:
 *
 *  - **Field** (`3040 x 320`): the box stretches horizontally, so it cannot be
 *    uniformly scaled. Left-edge features are anchored to the left and scaled by
 *    `h/320`; right-edge features are anchored to the right by the same factor;
 *    only the straight runs between them stretch. See [HyleFieldShape].
 *  - **Toggle** (`644 x 320`): fixed aspect, so a single uniform scale applies.
 *
 * Everything here is expressed in *source units*; call sites scale. Do not
 * "tidy" these numbers — they are measured, and the fidelity is the point.
 */
internal object CellPaths {

    /** Field authoring canvas. */
    const val FIELD_W = 3040f
    const val FIELD_H = 320f

    /** Toggle authoring canvas. */
    const val TOGGLE_W = 644f
    const val TOGGLE_H = 320f

    /**
     * The required-field marker for every non-error state: one unsplit
     * pill/comma riding the slant. Authored at the origin, 60 x 196.
     */
    const val MARKER_PILL =
        "M0 14C0 6.26802 6.26801 0 14 0H44.891C54.0544 0 60.7473 8.65735 58.4398 " +
            "17.5255L13.3176 190.936C12.5416 193.919 9.84921 196 6.76783 196C3.03006 " +
            "196 0 192.97 0 189.232V14Z"

    /** Error state splits the same silhouette into a literal exclamation: stem... */
    const val MARKER_STEM =
        "M0 14C0 6.26801 6.26801 0 14 0H44.9737C54.1115 0 60.7999 8.61195 58.5381 " +
            "17.4653L30.6913 126.465C29.1079 132.664 23.5243 137 17.127 137H14C6.26801 " +
            "137 0 130.732 0 123V14Z"

    /** ...and dot. */
    const val MARKER_DOT =
        "M0 172.094C0 167.071 4.07146 163 9.09386 163C15.0901 163 19.445 168.702 " +
            "17.8673 174.487L13.3673 190.987C12.56 193.947 9.87156 196 6.80351 " +
            "196C3.04603 196 0 192.954 0 189.196L0 172.094Z"

    /**
     * Five-point asterisk, right-anchored in the field canvas (x ~2790..2900).
     * Five arms, not six — the owner corrected this explicitly.
     */
    const val FIELD_ASTERISK =
        "M2818.92 208.665C2816.28 212.101 2811.38 212.812 2807.87 210.269C2804.24 " +
            "207.634 2803.48 202.525 2806.2 198.951L2828.58 169.498C2830.24 167.322 " +
            "2829.27 164.164 2826.68 163.288L2795.27 152.665C2791.21 151.292 2788.97 " +
            "146.954 2790.19 142.847C2791.47 138.555 2796.04 136.161 2800.29 " +
            "137.55L2831.88 147.85C2834.47 148.693 2837.12 146.766 2837.12 " +
            "144.047V110.7C2837.12 106.282 2840.78 102.7 2845.2 102.7C2849.62 102.7 " +
            "2853.28 106.282 2853.28 110.7V144.034C2853.28 146.757 2855.94 148.684 " +
            "2858.53 147.835L2890.03 137.486C2894.2 136.118 2898.71 138.42 2900.12 " +
            "142.571C2901.55 146.753 2899.33 151.36 2895.15 152.784L2864.32 " +
            "163.29C2861.74 164.168 2860.78 167.306 2862.41 169.481L2884.58 " +
            "198.959C2887.27 202.534 2886.47 207.643 2882.85 210.269C2879.35 212.802 " +
            "2874.45 212.13 2871.8 208.72L2848.38 178.535C2846.77 176.465 2843.64 " +
            "176.473 2842.04 178.551L2818.92 208.665Z"

    /** Toggle: the selected chip, occupying the right half of the well. */
    const val TOGGLE_CHIP =
        "M381.447 2H604C624.987 2.00002 642 19.0132 642 40V280C642 300.987 624.987 " +
            "318 604 318H316.305C291.268 318 273.072 294.209 279.631 " +
            "270.046L344.774 30.0459C349.197 13.7504 363.827 2.36087 380.645 " +
            "2.00879L381.447 2Z"

    /** Toggle: the glint bracket — a stroked open corner, not a filled shape. */
    const val TOGGLE_GLINT_BRACKET =
        "M578 28H591.959C604.132 28 614 37.868 614 50.0408V64"

    /** Toggle: the glint's trailing dot (x=610, y=80, 8x8, r4). */
    const val GLINT_DOT_X = 610f
    const val GLINT_DOT_Y = 80f
    const val GLINT_DOT_SIZE = 8f

    /** Toggle: the well's corner radius, and the chip stroke width. */
    const val TOGGLE_RADIUS = 40f
    const val TOGGLE_STROKE = 4f

    /** Field: ring stroke width in source units. */
    const val FIELD_RING_STROKE = 4f

    /**
     * Parse an authored path and place it with a uniform scale plus an x-offset.
     * [dx] is how far to push the result right — zero for left-anchored glyphs,
     * `w - FIELD_W * s` for right-anchored ones, which lands them the same
     * distance in from the right edge as they sit in the source.
     */
    fun scaled(pathData: String, scale: Float, dx: Float = 0f, dy: Float = 0f): Path {
        val path = PathParser().parsePathString(pathData).toPath()
        path.transform(
            Matrix().apply {
                translate(dx, dy)
                scale(scale, scale)
            },
        )
        return path
    }
}

/**
 * The field silhouette, transcribed control-point for control-point from the
 * owner's export. Left-edge features scale with height and stay left-anchored,
 * right-edge features stay right-anchored, and only the straight top/bottom runs
 * between them stretch — so the corners and the slant hold their authored shape
 * at any width.
 */
internal object HyleFieldShape : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        if (size.width <= 0f || size.height <= 0f) return Outline.Generic(Path())
        val w = size.width
        val s = size.height / CellPaths.FIELD_H
        // Left-anchored and right-anchored coordinate mappings.
        fun l(x: Float) = x * s
        fun r(x: Float) = w - (CellPaths.FIELD_W - x) * s
        fun y(v: Float) = v * s

        val path = Path().apply {
            moveTo(l(78.844f), y(29.522f))
            cubicTo(l(83.5738f), y(12.0965f), l(99.3913f), 0f, l(117.447f), 0f)
            lineTo(r(3000f), 0f)
            cubicTo(r(3022.09f), 0f, w, y(17.9086f), w, y(40f))
            lineTo(w, y(280f))
            cubicTo(w, y(302.091f), r(3022.09f), y(320f), r(3000f), y(320f))
            lineTo(l(52.3045f), y(320f))
            cubicTo(l(25.9496f), y(320f), l(6.7975f), y(294.957f), l(13.7012f), y(269.522f))
            lineTo(l(78.844f), y(29.522f))
            close()
        }
        return Outline.Generic(path)
    }
}
