package dev.fonebrew.domain.workspace.tree

import dev.fonebrew.domain.workspace.roots.DirEntry

/**
 * File-name ordering for the tree: case-insensitive, digit runs compared by numeric value
 * (`file2` < `file10`), so a person reads the list in the order they expect. Digit runs are
 * compared as strings after stripping leading zeros (length, then lexicographic), so arbitrarily
 * long numbers cannot overflow. Fully deterministic: ties fall to fewer leading zeros, then to the
 * case-sensitive code-unit order of the whole name.
 */
object NaturalOrder {

    val names: Comparator<String> = Comparator { a, b -> compareNames(a, b) }

    /** Directories first, then [names]. */
    val entries: Comparator<DirEntry> = Comparator { a, b ->
        if (a.isDir != b.isDir) { if (a.isDir) -1 else 1 } else compareNames(a.name, b.name)
    }

    fun compareNames(a: String, b: String): Int {
        var i = 0
        var j = 0
        var zerosTie = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isAsciiDigit() && cb.isAsciiDigit()) {
                var ie = i; while (ie < a.length && a[ie].isAsciiDigit()) ie++
                var je = j; while (je < b.length && b[je].isAsciiDigit()) je++
                var is0 = i; while (is0 < ie - 1 && a[is0] == '0') is0++
                var js0 = j; while (js0 < je - 1 && b[js0] == '0') js0++
                val la = ie - is0
                val lb = je - js0
                if (la != lb) return la.compareTo(lb)
                for (k in 0 until la) {
                    val d = a[is0 + k].compareTo(b[js0 + k])
                    if (d != 0) return d
                }
                if (zerosTie == 0) zerosTie = (is0 - i).compareTo(js0 - j)
                i = ie; j = je
            } else {
                val la = ca.lowercaseChar()
                val lb = cb.lowercaseChar()
                if (la != lb) return la.compareTo(lb)
                i++; j++
            }
        }
        val rest = (a.length - i).compareTo(b.length - j)
        if (rest != 0) return rest
        if (zerosTie != 0) return zerosTie
        return a.compareTo(b)
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'
}
