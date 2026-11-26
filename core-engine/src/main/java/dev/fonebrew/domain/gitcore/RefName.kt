package dev.fonebrew.domain.gitcore

/** git's ref-name rules (check-ref-format), as a pure validator. */
object RefName {
    private val PSEUDO = setOf("HEAD", "FETCH_HEAD", "ORIG_HEAD", "MERGE_HEAD", "CHERRY_PICK_HEAD")

    /** Null when [name] is a valid full ref name (`refs/...`) or one of the pseudo refs; else the reason. */
    fun violation(name: String): String? {
        if (name in PSEUDO) return null
        if (!name.startsWith("refs/")) return "must start with refs/"
        return componentRules(name)
    }

    /** Rules for a short name such as a branch (`feature/x`): same as a ref name minus the prefix requirement. */
    fun shortViolation(name: String): String? {
        if (name == "@") return "'@' is not a valid name"
        if (name.startsWith("-")) return "must not start with '-'"
        return componentRules(name)
    }

    private fun componentRules(name: String): String? {
        if (name.isEmpty()) return "empty"
        if (name.startsWith("/") || name.endsWith("/")) return "leading or trailing slash"
        if (name.endsWith(".")) return "ends with '.'"
        if (name.contains("..")) return "contains '..'"
        if (name.contains("@{")) return "contains '@{'"
        if (name.contains("//")) return "consecutive slashes"
        for (c in name) {
            if (c.code < 0x20 || c.code == 0x7f) return "control character"
            if (c in " ~^:?*[\\") return "forbidden character '$c'"
        }
        for (comp in name.split('/')) {
            if (comp.startsWith(".")) return "component starts with '.'"
            if (comp.endsWith(".lock")) return "component ends with '.lock'"
            if (PathSafety.isGitDirName(comp)) return "component names the git directory"
        }
        return null
    }

    fun requireValid(name: String) {
        violation(name)?.let { throw GitException(GitErrorKind.INVALID_REF_NAME, "invalid ref name '${PathSafety.display(name)}': $it") }
    }

    fun requireValidShort(name: String) {
        shortViolation(name)?.let { throw GitException(GitErrorKind.INVALID_REF_NAME, "invalid name '${PathSafety.display(name)}': $it") }
    }

    fun isValid(name: String) = violation(name) == null
}
