package dev.fonebrew.domain.gitcore.transport

/**
 * A parsed capability list: `name` or `name=value` tokens. A name may repeat (`symref=` does), so
 * values are kept as a list per name. Unknown capabilities are carried, never rejected.
 */
class Capabilities(private val map: Map<String, List<String?>>) {
    fun has(name: String) = map.containsKey(name)
    fun value(name: String): String? = map[name]?.lastOrNull()
    fun values(name: String): List<String> = map[name].orEmpty().filterNotNull()
    val names: Set<String> get() = map.keys

    /** For v2 `fetch=shallow wait-for-done` style values: the space-separated features. */
    fun features(name: String): Set<String> = value(name)?.split(' ')?.filter { it.isNotEmpty() }?.toSet().orEmpty()

    companion object {
        val EMPTY = Capabilities(emptyMap())

        /** v2 form: one capability per line, `name` or `name=value` where the value may hold spaces. */
        fun fromLines(lines: List<String>): Capabilities {
            val m = LinkedHashMap<String, MutableList<String?>>()
            for (l in lines) {
                val eq = l.indexOf('=')
                if (eq < 0) m.getOrPut(l) { ArrayList() }.add(null)
                else m.getOrPut(l.substring(0, eq)) { ArrayList() }.add(l.substring(eq + 1))
            }
            return Capabilities(m)
        }

        fun parse(text: String): Capabilities {
            val m = LinkedHashMap<String, MutableList<String?>>()
            for (tok in text.split(' ')) {
                if (tok.isEmpty()) continue
                val eq = tok.indexOf('=')
                if (eq < 0) m.getOrPut(tok) { ArrayList() }.add(null)
                else m.getOrPut(tok.substring(0, eq)) { ArrayList() }.add(tok.substring(eq + 1))
            }
            return Capabilities(m)
        }
    }
}
