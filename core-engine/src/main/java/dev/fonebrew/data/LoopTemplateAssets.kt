package dev.fonebrew.data

import android.content.Context

/**
 * Loads a `.floop.json` loop-package template bundled under `core-engine/src/main/assets/loops/`
 * — today just [LOCALIZATION_DRAFT] (Studio P8's OSS content-localization dependency,
 * `docs/STATE.md`). The same asset-plus-thin-Context-loader split [ModelCatalogStore]/
 * [FreeTierStore] already use for their own bundled JSON snapshots — no new asset-loading
 * mechanism, and no bespoke import path either: [read] hands back the raw package bytes, and a
 * caller feeds them into [dev.fonebrew.domain.loop.LoopImportPresenter] (or
 * [dev.fonebrew.domain.loop.LoopPackageCodec] directly) exactly as it would any user-picked
 * `.floop.json` file — same content-safety scan, same reviewer preview, same **UNSIGNED**
 * posture (no publisher-key infrastructure exists in this codebase; see
 * `LoopPackageCodec`'s own KDoc). Bundling a template in `assets/` is not a trust shortcut.
 *
 * Pure I/O wrapper, one line of real logic — everything worth JVM-testing (the template parses,
 * validates, and runs) lives in [dev.fonebrew.domain.loop.LocalizationDraftLoopTest], which reads
 * the same committed file directly off disk (no `Context` needed for that). This class exists so
 * the on-device import screen has an actual [Context]-bound entry point once Studio (or a future
 * in-core "browse templates" screen) wants one; it is not itself exercised by the JVM gate — no
 * `android.content.Context` implementation exists there, same honest limitation every other
 * `Context`-bound loader in `data/` carries.
 */
class LoopTemplateAssets(context: Context) {

    private val appContext = context.applicationContext

    /** Raw bytes of the named template under `assets/loops/` — pass straight to
     *  [dev.fonebrew.domain.loop.LoopPackageCodec.decode]/`.scan()`, or to
     *  [dev.fonebrew.domain.loop.LoopImportPresenter.run]. Throws [java.io.IOException] if the
     *  asset is missing — never returns a silent empty package. */
    fun read(templateAsset: String): ByteArray =
        appContext.assets.open("loops/$templateAsset").use { it.readBytes() }

    companion object {
        /** locale/fieldKey/sourceText params-driven translation-draft template — see its own
         *  bundled `docs/README.md` (inside the package) for the full param contract. */
        const val LOCALIZATION_DRAFT = "localization-draft.floop.json"
    }
}
