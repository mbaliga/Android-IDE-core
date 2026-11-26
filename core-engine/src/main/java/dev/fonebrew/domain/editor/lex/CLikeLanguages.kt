package dev.fonebrew.domain.editor.lex

/** Hand-written configurations for the C-family and scripting languages. Keyword lists are the languages' own. */
internal object CLikeLanguages {
    private fun words(s: String) = s.trim().split(Regex("\\s+")).toSet()

    private val DQ = StringDelim("\"")
    private val SQ = StringDelim("'")

    val KOTLIN = CLikeConfig(
        id = "kotlin",
        keywords = words(
            """as break class continue do else false for fun if in interface is null object package return super this
               throw true try typealias typeof val var when while by catch constructor delegate dynamic field file finally
               get import init param property receiver set setparam value where abstract actual annotation companion const
               crossinline data enum expect external final infix inline inner internal lateinit noinline open operator out
               override private protected public reified sealed suspend tailrec vararg""",
        ),
        nestedBlockComments = true,
        delims = listOf(StringDelim("\"\"\"", escapes = false, multiline = true), DQ, SQ),
        annotations = true,
    )

    val JAVA = CLikeConfig(
        id = "java",
        keywords = words(
            """abstract assert break case catch class const continue default do else enum extends final finally for goto if
               implements import instanceof interface native new package private protected public return static strictfp
               super switch synchronized this throw throws transient try volatile while true false null var record sealed
               permits yield non-sealed""",
        ),
        builtinTypes = words("boolean byte char double float int long short void"),
        delims = listOf(StringDelim("\"\"\"", multiline = true), DQ, SQ),
        annotations = true,
    )

    private val JS_KEYWORDS = words(
        """async await break case catch class const continue debugger default delete do else export extends false finally
           for from function if import in instanceof let new null of return static super switch this throw true try typeof
           undefined var void while with yield as get set enum implements interface package private protected public
           abstract declare namespace type readonly keyof is infer satisfies override""",
    )
    val JAVASCRIPT = CLikeConfig(
        id = "javascript",
        keywords = JS_KEYWORDS,
        builtinTypes = words("string number boolean any unknown never object symbol bigint void"),
        delims = listOf(StringDelim("`", multiline = true), DQ, SQ),
        annotations = true,
        dollarInIdentifiers = true,
    )

    val PYTHON = CLikeConfig(
        id = "python",
        keywords = words(
            """False None True and as assert async await break class continue def del elif else except finally for from
               global if import in is lambda nonlocal not or pass raise return try while with yield match case self""",
        ),
        builtinTypes = words("int float str bool bytes list dict set tuple object type complex"),
        lineComments = listOf("#"),
        blockComment = null,
        delims = listOf(StringDelim("\"\"\"", multiline = true), StringDelim("'''", multiline = true), DQ, SQ),
        stringPrefixes = words("r b f u rb br fr rf"),
        annotations = true,
        operatorChars = "+-*/%=<>!&|^~:",
    )

    val SHELL = CLikeConfig(
        id = "shell",
        keywords = words("if then else elif fi for while until do done case esac in function select time coproc"),
        builtinTypes = emptySet(),
        lineComments = listOf("#"),
        blockComment = null,
        delims = listOf(StringDelim("\"", multiline = true), StringDelim("'", escapes = false, multiline = true)),
        shell = true,
        capitalisedAreTypes = false,
        operatorChars = "=<>!&|^~?:+*/%-",
        punctuationChars = "(){}[];,",
    )

    val C = CLikeConfig(
        id = "c",
        keywords = words(
            """auto break case const continue default do else enum extern for goto if inline register restrict return
               sizeof static struct switch typedef union volatile while NULL true false _Bool _Alignas _Alignof _Atomic
               _Generic _Noreturn _Static_assert _Thread_local""",
        ),
        builtinTypes = words("char double float int long short signed unsigned void bool size_t int8_t int16_t int32_t int64_t uint8_t uint16_t uint32_t uint64_t"),
        delims = listOf(DQ, SQ),
        preprocessor = true,
        capitalisedAreTypes = false,
    )

    val CPP = CLikeConfig(
        id = "cpp",
        keywords = words(
            """alignas alignof and and_eq asm auto bitand bitor break case catch class compl concept const consteval
               constexpr constinit const_cast continue co_await co_return co_yield decltype default delete do dynamic_cast
               else enum explicit export extern false for friend goto if inline mutable namespace new noexcept not not_eq
               nullptr operator or or_eq private protected public register reinterpret_cast requires return sizeof static
               static_assert static_cast struct switch template this thread_local throw true try typedef typeid typename
               union using virtual volatile while xor xor_eq override final NULL""",
        ),
        builtinTypes = words("char double float int long short signed unsigned void bool wchar_t char8_t char16_t char32_t size_t auto"),
        delims = listOf(DQ, SQ),
        preprocessor = true,
    )

    val RUST = CLikeConfig(
        id = "rust",
        keywords = words(
            """as async await break const continue crate dyn else enum extern false fn for if impl in let loop match mod
               move mut pub ref return self Self static struct super trait true type unsafe use where while union macro_rules""",
        ),
        builtinTypes = words("i8 i16 i32 i64 i128 isize u8 u16 u32 u64 u128 usize f32 f64 bool char str"),
        nestedBlockComments = true,
        delims = listOf(StringDelim("\"", multiline = true), SQ),
        rustRaw = true,
        rustLifetimes = true,
        macroBang = true,
    )

    val GO = CLikeConfig(
        id = "go",
        keywords = words(
            """break case chan const continue default defer else fallthrough for func go goto if import interface map
               package range return select struct switch type var true false nil iota""",
        ),
        builtinTypes = words("bool byte complex64 complex128 error float32 float64 int int8 int16 int32 int64 rune string uint uint8 uint16 uint32 uint64 uintptr any"),
        delims = listOf(StringDelim("`", escapes = false, multiline = true), DQ, SQ),
        capitalisedAreTypes = false,
    )

    val GRADLE = CLikeConfig(
        id = "gradle",
        keywords = words(
            """def val var fun if else for while return class interface object import package new null true false in as
               it this super try catch finally throw switch case default break continue extends implements
               plugins dependencies repositories buildscript allprojects subprojects android sourceSets tasks task
               ext apply include pluginManagement dependencyResolutionManagement rootProject""",
        ),
        delims = listOf(
            StringDelim("\"\"\"", multiline = true), StringDelim("'''", multiline = true), DQ, SQ,
        ),
        nestedBlockComments = true,
        dollarInIdentifiers = false,
    )
}
