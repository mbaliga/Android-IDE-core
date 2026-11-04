package dev.fonebrew.domain.loop

/**
 * Metadata for one bundled loop-**package** template under
 * `core-engine/src/main/assets/loops/` — the `.floop.json` shape [LoopPackageCodec]/
 * [LoopImportPresenter] handle, not [LoopCatalog]'s bare reference patterns (MoA/
 * self-consistency/reflexion/debate — those seed straight in as [Loop] rows via
 * [LoopCatalogSeeder], no package/import step). [assetName] is the file
 * [dev.fonebrew.data.LoopTemplateAssets.read] loads.
 */
data class LoopTemplateInfo(
    val assetName: String,
    val displayName: String,
    val description: String,
)

/**
 * The browse list for LoopRoom's "Templates" affordance (asoc-reachability audit item 5,
 * 2026-09-15): [dev.fonebrew.data.LoopTemplateAssets] had a loader with no caller — its own
 * KDoc said it "awaits a browse-templates entry point." This is that entry point's data, kept
 * pure/Context-free so the listing itself (non-empty, names non-blank, asset names unique) is
 * JVM-testable without an Android [android.content.Context] — see [LoopTemplateCatalogTest],
 * which also cross-checks every [LoopTemplateInfo.assetName] here against
 * [dev.fonebrew.data.LoopTemplateAssets]'s own constants and against the real committed file on
 * disk (the same technique [dev.fonebrew.domain.loop.LocalizationDraftLoopTest] already uses),
 * so this catalog can't silently drift from what's actually bundled.
 *
 * Picking an entry does NOT install it directly — [dev.fonebrew.ui.loops.LoopRoom] reads the
 * bytes via [dev.fonebrew.data.LoopTemplateAssets] and hands them to the SAME
 * `ImportLoopPackageDialog` (LoopPackageCodec decode+scan, then the authority-review screen) a
 * user-picked `.floop.json` gets — bundling is not a trust shortcut (see that class's KDoc).
 */
object LoopTemplateCatalog {

    val ALL: List<LoopTemplateInfo> = listOf(
        LoopTemplateInfo(
            assetName = "localization-draft.floop.json",
            displayName = "Localization draft",
            description = "Translate one UI string into a target locale. Params: locale, fieldKey, sourceText.",
        ),
    )
}
