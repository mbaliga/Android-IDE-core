package dev.fonebrew.domain.language

import dev.fonebrew.contracts.language.LanguagePackManifest

/**
 * WP-9's "diagnostics ownership": which installed [LanguagePackManifest] is allowed to publish
 * diagnostics for a given file. Two language packs both claiming `.h` (a real-world case — a C
 * pack and a C++ pack both often want header files) MUST NOT both publish diagnostics for the
 * same file silently; duplicated, possibly-contradictory diagnostics on one file is a worse
 * failure mode than no diagnostics at all, so this resolver fails closed into
 * [Ownership.Conflicted] rather than picking a winner by accident of list order.
 */
object DiagnosticsOwnership {

    sealed interface Ownership {
        data class Owned(val pack: LanguagePackManifest) : Ownership
        object NoOwner : Ownership
        data class Conflicted(val candidates: List<LanguagePackManifest>) : Ownership
    }

    /** [fileExtension] MUST include the leading '.', matching [LanguagePackManifest.fileExtensions]'s own stored form. */
    fun resolve(installedPacks: List<LanguagePackManifest>, fileExtension: String): Ownership {
        val candidates = installedPacks.filter { fileExtension in it.fileExtensions }
        return when (candidates.size) {
            0 -> Ownership.NoOwner
            1 -> Ownership.Owned(candidates.single())
            else -> Ownership.Conflicted(candidates)
        }
    }

    /** Every extension claimed by more than one pack in [installedPacks] -- a preflight check a "would this installation set even be legal together" surface can run before accepting a second pack for an already-claimed extension. */
    fun findAllConflicts(installedPacks: List<LanguagePackManifest>): Map<String, List<LanguagePackManifest>> {
        val byExtension = mutableMapOf<String, MutableList<LanguagePackManifest>>()
        for (pack in installedPacks) {
            for (extension in pack.fileExtensions) {
                byExtension.getOrPut(extension) { mutableListOf() }.add(pack)
            }
        }
        return byExtension.filterValues { it.size > 1 }
    }
}
