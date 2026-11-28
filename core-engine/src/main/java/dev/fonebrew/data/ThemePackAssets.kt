package dev.fonebrew.data

import android.content.Context
import dev.fonebrew.domain.extension.ThemePackCatalog

/** Reads the theme packs bundled under `assets/extensions/themes/` and hands them to the pure
 *  [ThemePackCatalog]. Only bundled packs exist today; user-installed packs arrive with the
 *  install flow (docs/design/extensions.md §6). */
object ThemePackAssets {
    private const val DIR = "extensions/themes"

    fun load(context: Context): ThemePackCatalog.Loaded {
        val names = runCatching { context.assets.list(DIR)?.toList().orEmpty() }.getOrDefault(emptyList())
            .filter { it.endsWith(".theme.json") }
        val docs = names.associateWith { name ->
            context.assets.open("$DIR/$name").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        return ThemePackCatalog.load(docs)
    }
}
