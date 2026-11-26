package dev.fonebrew.domain.layout

import dev.fonebrew.domain.layout.LayoutNode.Companion.leaf
import dev.fonebrew.domain.layout.LayoutNode.Companion.split

enum class PresetId { ULTRAWIDE_CODING, ULTRAWIDE_REVIEW, DESKTOP_CODING, TABLET_TWO_PANE, PHONE_STACK }

/**
 * A starting layout for a [ScreenClass]. **Every preset is a PLACEHOLDER** (see [placeholder]):
 * the arrangements and fractions below are a first guess, to be adjusted once the owner has
 * approved the docked-layout design and seen it on the real display.
 */
data class LayoutPreset(val id: PresetId, val screenClass: ScreenClass, val layout: PaneLayout, val placeholder: Boolean = true)

object PaneLayoutPresets {
    /** Loud on purpose: nothing may treat these arrangements as decided. */
    const val PRESETS_ARE_PLACEHOLDERS = true

    private fun layout(root: LayoutNode, focus: PaneKind) = PaneLayout(root, PaneId(focus, 1))

    /** Rail + tree + two editors over a terminal + assistant. */
    val ultrawideCoding: LayoutPreset = LayoutPreset(
        PresetId.ULTRAWIDE_CODING, ScreenClass.ULTRAWIDE,
        layout(
            split(
                Axis.ROW,
                leaf(PaneKind.RAIL) to 0.02f,
                leaf(PaneKind.TREE) to 0.12f,
                split(
                    Axis.COLUMN,
                    split(Axis.ROW, leaf(PaneKind.EDITOR, 1) to 0.5f, leaf(PaneKind.EDITOR, 2) to 0.5f) to 0.72f,
                    leaf(PaneKind.TERMINAL) to 0.28f,
                ) to 0.62f,
                leaf(PaneKind.ASSISTANT) to 0.24f,
            ),
            PaneKind.EDITOR,
        ),
    )

    /** The diff (hosted by the GIT pane) beside a terminal. */
    val ultrawideReview: LayoutPreset = LayoutPreset(
        PresetId.ULTRAWIDE_REVIEW, ScreenClass.ULTRAWIDE,
        layout(split(Axis.ROW, leaf(PaneKind.GIT) to 0.65f, leaf(PaneKind.TERMINAL) to 0.35f), PaneKind.GIT),
    )

    val desktopCoding: LayoutPreset = LayoutPreset(
        PresetId.DESKTOP_CODING, ScreenClass.DESKTOP,
        layout(
            split(
                Axis.ROW,
                leaf(PaneKind.TREE) to 0.18f,
                split(Axis.COLUMN, leaf(PaneKind.EDITOR) to 0.75f, leaf(PaneKind.TERMINAL) to 0.25f) to 0.82f,
            ),
            PaneKind.EDITOR,
        ),
    )

    val tabletTwoPane: LayoutPreset = LayoutPreset(
        PresetId.TABLET_TWO_PANE, ScreenClass.TABLET,
        layout(split(Axis.ROW, leaf(PaneKind.TREE) to 0.3f, leaf(PaneKind.EDITOR) to 0.7f), PaneKind.EDITOR),
    )

    /** One pane at a time; the other kinds are reached as a stack by the (phone) shell, not split in. */
    val phoneStack: LayoutPreset = LayoutPreset(
        PresetId.PHONE_STACK, ScreenClass.PHONE, layout(leaf(PaneKind.EDITOR), PaneKind.EDITOR),
    )

    val all: List<LayoutPreset> = listOf(ultrawideCoding, ultrawideReview, desktopCoding, tabletTwoPane, phoneStack)

    /** The default preset for a class (ULTRAWIDE defaults to coding; review is chosen explicitly). */
    fun forClass(c: ScreenClass): LayoutPreset = when (c) {
        ScreenClass.ULTRAWIDE -> ultrawideCoding
        ScreenClass.DESKTOP -> desktopCoding
        ScreenClass.TABLET -> tabletTwoPane
        ScreenClass.PHONE -> phoneStack
    }
}
