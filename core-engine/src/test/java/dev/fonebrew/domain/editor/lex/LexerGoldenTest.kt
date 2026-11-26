package dev.fonebrew.domain.editor.lex

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Golden token tests: for each snippet, the non-plain, non-punctuation, non-operator tokens as
 * `CATEGORY:text`, joined by `|`, across all lines of the snippet.
 */
class LexerGoldenTest {
    private data class Case(val lang: String, val src: String, val expected: String)

    private fun lexAll(lang: String, src: String, keep: (TokenCategory) -> Boolean): String {
        val l = Languages.byId(lang).lexer
        var st = LexState.INITIAL
        val out = ArrayList<String>()
        for (line in src.split("\n")) {
            val sink = TokenSink(line.length)
            st = l.lexLine(line, st, sink)
            for (t in sink.build()) if (keep(t.category)) out += "${t.category}:${line.substring(t.start, t.end)}"
        }
        return out.joinToString("|")
    }

    private val cases = listOf(
        // ---- Kotlin
        Case("kotlin", "fun main() {", "KEYWORD:fun|FUNCTION:main"),
        Case("kotlin", "val x: Int = 42", "KEYWORD:val|TYPE:Int|NUMBER:42"),
        Case("kotlin", "// comment here", "COMMENT:// comment here"),
        Case("kotlin", "val s = \"hi\\n there\"", "KEYWORD:val|STRING:\"hi|ESCAPE:\\n|STRING: there\""),
        Case("kotlin", "/* a\n b */ val", "COMMENT:/* a|COMMENT: b */|KEYWORD:val"),
        Case("kotlin", "val r = \"\"\"\nline \"quoted\"\nend\"\"\"", "KEYWORD:val|STRING:\"\"\"|STRING:line \"quoted\"|STRING:end\"\"\""),
        Case("kotlin", "@Composable fun Foo(x: Int)", "TYPE:@Composable|KEYWORD:fun|FUNCTION:Foo|TYPE:Int"),
        Case("kotlin", "/* a /* b */ c */ x", "COMMENT:/* a /* b */ c */"),
        Case("kotlin", "val c = 'x' + 1.5f", "KEYWORD:val|STRING:'x'|NUMBER:1.5f"),
        Case("kotlin", "val s = \"oops", "KEYWORD:val|STRING:\"oops"),
        // ---- Java
        Case("java", "public static void main(String[] args) {", "KEYWORD:public|KEYWORD:static|TYPE:void|FUNCTION:main|TYPE:String"),
        Case("java", "int x = 0b101 + 1_000L;", "TYPE:int|NUMBER:0b101|NUMBER:1_000L"),
        Case("java", "@Override", "TYPE:@Override"),
        Case("java", "// note", "COMMENT:// note"),
        Case("java", "/** doc */ int y;", "COMMENT:/** doc */|TYPE:int"),
        Case("java", "String s = \"\"\"\n  hi\n\"\"\";", "TYPE:String|STRING:\"\"\"|STRING:  hi|STRING:\"\"\""),
        Case("java", "char c = '\\n';", "TYPE:char|STRING:'|ESCAPE:\\n|STRING:'"),
        Case("java", "if (x == null) return;", "KEYWORD:if|KEYWORD:null|KEYWORD:return"),
        Case("java", "new Foo<>()", "KEYWORD:new|TYPE:Foo"),
        Case("java", "String t = \"a\\\"b\";", "TYPE:String|STRING:\"a|ESCAPE:\\\"|STRING:b\""),
        // ---- JavaScript / TypeScript
        Case("javascript", "const f = (a) => a * 2;", "KEYWORD:const|NUMBER:2"),
        Case("javascript", "function greet(name) {", "KEYWORD:function|FUNCTION:greet"),
        Case("javascript", "let t = `a\nb \${x}`;", "KEYWORD:let|STRING:`a|STRING:b \${x}`"),
        Case("javascript", "// c", "COMMENT:// c"),
        Case("javascript", "/* a */ x", "COMMENT:/* a */"),
        Case("javascript", "let n = 0xFF + 1e3;", "KEYWORD:let|NUMBER:0xFF|NUMBER:1e3"),
        Case("javascript", "class Foo extends Bar {}", "KEYWORD:class|TYPE:Foo|KEYWORD:extends|TYPE:Bar"),
        Case("javascript", "type T = string | number;", "KEYWORD:type|TYPE:string|TYPE:number"),
        Case("javascript", "x = 'it\\'s';", "STRING:'it|ESCAPE:\\'|STRING:s'"),
        Case("javascript", "await fetch(url)", "KEYWORD:await|FUNCTION:fetch"),
        // ---- Python
        Case("python", "def foo(x):", "KEYWORD:def|FUNCTION:foo"),
        Case("python", "# comment", "COMMENT:# comment"),
        Case("python", "s = f\"hi {x}\"", "STRING:f\"hi {x}\""),
        Case("python", "\"\"\"doc\nmore\"\"\"", "STRING:\"\"\"doc|STRING:more\"\"\""),
        Case("python", "if x is None and not y:", "KEYWORD:if|KEYWORD:is|KEYWORD:None|KEYWORD:and|KEYWORD:not"),
        Case("python", "@decorator", "TYPE:@decorator"),
        Case("python", "x = 0x1f + 3.14e-2", "NUMBER:0x1f|NUMBER:3.14e-2"),
        Case("python", "x = r\"\\d+\"", "STRING:r\"\\d+\""),
        Case("python", "class Foo(Base):", "KEYWORD:class|FUNCTION:Foo|TYPE:Base"),
        Case("python", "print(\"a\\tb\")", "FUNCTION:print|STRING:\"a|ESCAPE:\\t|STRING:b\""),
        // ---- Shell
        Case("shell", "echo \"hello \$USER\"", "STRING:\"hello \$USER\""),
        Case("shell", "if [ -f file ]; then", "KEYWORD:if|KEYWORD:then"),
        Case("shell", "# comment", "COMMENT:# comment"),
        Case("shell", "x=\$HOME/bin", "PROPERTY:\$HOME"),
        Case("shell", "cat <<EOF\ntext \$x\nEOF\necho fin", "STRING:text \$x|STRING:EOF"),
        Case("shell", "for i in 1 2; do", "KEYWORD:for|KEYWORD:in|NUMBER:1|NUMBER:2|KEYWORD:do"),
        Case("shell", "echo 'single \$x'", "STRING:'single \$x'"),
        Case("shell", "echo a#b # real", "COMMENT:# real"),
        Case("shell", "echo \${VAR:-default}", "PROPERTY:\${VAR:-default}"),
        Case("shell", "echo \"a\nb\"", "STRING:\"a|STRING:b\""),
        // ---- C
        Case("c", "#include <stdio.h>", "KEYWORD:#include|STRING:<stdio.h>"),
        Case("c", "int main(void) {", "TYPE:int|FUNCTION:main|TYPE:void"),
        Case("c", "#define MAX 10", "KEYWORD:#define|NUMBER:10"),
        Case("c", "/* multi\nline */ int x;", "COMMENT:/* multi|COMMENT:line */|TYPE:int"),
        Case("c", "char *s = \"a\\n\";", "TYPE:char|STRING:\"a|ESCAPE:\\n|STRING:\""),
        Case("c", "// c", "COMMENT:// c"),
        Case("c", "struct foo { unsigned long n; };", "KEYWORD:struct|TYPE:unsigned|TYPE:long"),
        Case("c", "x = 0x7fU;", "NUMBER:0x7fU"),
        Case("c", "if (p == NULL) return -1;", "KEYWORD:if|KEYWORD:NULL|KEYWORD:return|NUMBER:1"),
        Case("c", "char c = '\\0';", "TYPE:char|STRING:'|ESCAPE:\\0|STRING:'"),
        // ---- C++
        Case("cpp", "#include <vector>", "KEYWORD:#include|STRING:<vector>"),
        Case("cpp", "std::vector<int> v;", "TYPE:int"),
        Case("cpp", "class Foo : public Bar {", "KEYWORD:class|TYPE:Foo|KEYWORD:public|TYPE:Bar"),
        Case("cpp", "auto x = nullptr;", "KEYWORD:auto|KEYWORD:nullptr"),
        Case("cpp", "template <typename T>", "KEYWORD:template|KEYWORD:typename"),
        Case("cpp", "// c", "COMMENT:// c"),
        Case("cpp", "/* a */ int z;", "COMMENT:/* a */|TYPE:int"),
        Case("cpp", "constexpr int N = 5;", "KEYWORD:constexpr|TYPE:int|NUMBER:5"),
        Case("cpp", "std::cout << \"hi\" << std::endl;", "STRING:\"hi\""),
        Case("cpp", "namespace ns { }", "KEYWORD:namespace"),
        // ---- Rust
        Case("rust", "fn main() {", "KEYWORD:fn|FUNCTION:main"),
        Case("rust", "let mut x: u32 = 5;", "KEYWORD:let|KEYWORD:mut|TYPE:u32|NUMBER:5"),
        Case("rust", "println!(\"hi {}\", x);", "FUNCTION:println!|STRING:\"hi {}\""),
        Case("rust", "fn f<'a>(x: &'a str)", "KEYWORD:fn|TYPE:'a|TYPE:'a|TYPE:str"),
        Case("rust", "let s = r#\"a \"q\" b\"#;", "KEYWORD:let|STRING:r#\"a \"q\" b\"#"),
        Case("rust", "let s = \"a\nb\";", "KEYWORD:let|STRING:\"a|STRING:b\""),
        Case("rust", "let c = 'x';", "KEYWORD:let|STRING:'x'"),
        Case("rust", "/* a /* nested */ b */", "COMMENT:/* a /* nested */ b */"),
        Case("rust", "struct Foo;", "KEYWORD:struct|TYPE:Foo"),
        Case("rust", "let y = 1_000u64;", "KEYWORD:let|NUMBER:1_000u64"),
        // ---- Go
        Case("go", "func main() {", "KEYWORD:func|FUNCTION:main"),
        Case("go", "var x int = 3", "KEYWORD:var|TYPE:int|NUMBER:3"),
        Case("go", "s := `a\nb`", "STRING:`a|STRING:b`"),
        Case("go", "// c", "COMMENT:// c"),
        Case("go", "/* x */ y", "COMMENT:/* x */"),
        Case("go", "fmt.Println(\"hi\\n\")", "FUNCTION:Println|STRING:\"hi|ESCAPE:\\n|STRING:\""),
        Case("go", "type T struct {", "KEYWORD:type|KEYWORD:struct"),
        Case("go", "if err != nil {", "KEYWORD:if|KEYWORD:nil"),
        Case("go", "r := 'x'", "STRING:'x'"),
        Case("go", "for i := range xs {", "KEYWORD:for|KEYWORD:range"),
        // ---- Gradle
        Case("gradle", "plugins {", "KEYWORD:plugins"),
        Case("gradle", "id 'com.android.application'", "STRING:'com.android.application'"),
        Case("gradle", "implementation(\"a:b:1.0\")", "FUNCTION:implementation|STRING:\"a:b:1.0\""),
        Case("gradle", "// c", "COMMENT:// c"),
        Case("gradle", "/* c */ x", "COMMENT:/* c */"),
        Case("gradle", "def x = 5", "KEYWORD:def|NUMBER:5"),
        Case("gradle", "task t { println '''a\nb''' }", "KEYWORD:task|STRING:'''a|STRING:b'''"),
        Case("gradle", "dependencies {", "KEYWORD:dependencies"),
        Case("gradle", "val v = \"x\"", "KEYWORD:val|STRING:\"x\""),
        Case("gradle", "compileSdk = 36", "NUMBER:36"),
        // ---- JSON
        Case("json", "{\"a\": 1}", "PROPERTY:\"a\"|NUMBER:1"),
        Case("json", "{\"s\": \"x\"}", "PROPERTY:\"s\"|STRING:\"x\""),
        Case("json", "[true, false, null]", "KEYWORD:true|KEYWORD:false|KEYWORD:null"),
        Case("json", "\"a\\nb\"", "STRING:\"a|ESCAPE:\\n|STRING:b\""),
        Case("json", "-1.5e+3", "NUMBER:-1.5e+3"),
        Case("json", "01", "INVALID:01"),
        Case("json", "// c", "COMMENT:// c"),
        Case("json", "/* a\nb */ 1", "COMMENT:/* a|COMMENT:b */|NUMBER:1"),
        Case("json", "\"abc", "INVALID:\"abc"),
        Case("json", "nope", "INVALID:nope"),
        Case("json", "{\"k\":[1,2]}", "PROPERTY:\"k\"|NUMBER:1|NUMBER:2"),
        // ---- YAML
        Case("yaml", "name: app", "PROPERTY:name"),
        Case("yaml", "count: 3", "PROPERTY:count|NUMBER:3"),
        Case("yaml", "# c", "COMMENT:# c"),
        Case("yaml", "- \"x\"", "STRING:\"x\""),
        Case("yaml", "enabled: true", "PROPERTY:enabled|KEYWORD:true"),
        Case("yaml", "script: |\n  echo hi\n  done\nnext: 1", "PROPERTY:script|STRING:  echo hi|STRING:  done|PROPERTY:next|NUMBER:1"),
        Case("yaml", "base: &b\nref: *b", "PROPERTY:base|TYPE:&b|PROPERTY:ref|TYPE:*b"),
        Case("yaml", "url: http://x.y # note", "PROPERTY:url|COMMENT:# note"),
        Case("yaml", "---\nkey: 'a'", "PROPERTY:key|STRING:'a'"),
        Case("yaml", "\"quoted key\": 1", "PROPERTY:\"quoted key\"|NUMBER:1"),
        // ---- TOML
        Case("toml", "[package]", "TYPE:[package]"),
        Case("toml", "name = \"app\"", "PROPERTY:name|STRING:\"app\""),
        Case("toml", "n = 42", "PROPERTY:n|NUMBER:42"),
        Case("toml", "# c", "COMMENT:# c"),
        Case("toml", "on = true", "PROPERTY:on|KEYWORD:true"),
        Case("toml", "a.b.c = 1", "PROPERTY:a|PROPERTY:b|PROPERTY:c|NUMBER:1"),
        Case("toml", "s = \"\"\"\nline \\n\n\"\"\"", "PROPERTY:s|STRING:\"\"\"|STRING:line \\n|STRING:\"\"\""),
        Case("toml", "d = 1979-05-27T07:32:00Z", "PROPERTY:d|NUMBER:1979-05-27T07:32:00Z"),
        Case("toml", "[[bin]]", "TYPE:[[bin]]"),
        Case("toml", "arr = [1, \"a\"]", "PROPERTY:arr|NUMBER:1|STRING:\"a\""),
        // ---- Markdown
        Case("markdown", "# Title", "KEYWORD:# Title"),
        Case("markdown", "use `code` here", "STRING:`code`"),
        Case("markdown", "```kotlin\nval x = 1\n```\nafter", "TYPE:kotlin|STRING:val x = 1"),
        Case("markdown", "[link](http://a.b)", "FUNCTION:link|STRING:http://a.b"),
        Case("markdown", "\\*not em\\*", "ESCAPE:\\*|ESCAPE:\\*"),
        Case("markdown", "<https://x.org>", "STRING:<https://x.org>"),
        Case("markdown", "## Sub", "KEYWORD:## Sub"),
        Case("markdown", "~~~\nx\n~~~", "STRING:x"),
        Case("markdown", "```\ncode", "STRING:code"),
        Case("markdown", "![alt](i.png)", "FUNCTION:alt|STRING:i.png"),
        // ---- HTML / XML
        Case("markup", "<div class=\"a\">", "KEYWORD:div|PROPERTY:class|STRING:\"a\""),
        Case("markup", "<!-- c -->", "COMMENT:<!-- c -->"),
        Case("markup", "<p>x &amp; y</p>", "KEYWORD:p|ESCAPE:&amp;|KEYWORD:p"),
        Case("markup", "<!DOCTYPE html>", "KEYWORD:DOCTYPE|PROPERTY:html"),
        Case("markup", "<!-- a\nb -->", "COMMENT:<!-- a|COMMENT:b -->"),
        Case("markup", "<a href=\"x\"\n   target=_blank>", "KEYWORD:a|PROPERTY:href|STRING:\"x\"|PROPERTY:target|STRING:_blank"),
        Case("markup", "<script>\nif (a < b) {}\n</script>", "KEYWORD:script|KEYWORD:script"),
        Case("markup", "<?xml version=\"1.0\"?>", "KEYWORD:xml|PROPERTY:version|STRING:\"1.0\""),
        Case("markup", "<br/>", "KEYWORD:br"),
        Case("markup", "<![CDATA[x]]>", "STRING:<![CDATA[x]]>"),
        // ---- CSS
        Case("css", "a { color: red; }", "TYPE:a|PROPERTY:color"),
        Case("css", ".box > p { margin: 0 auto; }", "TYPE:.box|TYPE:p|PROPERTY:margin|NUMBER:0"),
        Case("css", "/* c */ a {}", "COMMENT:/* c */|TYPE:a"),
        Case("css", "@media (max-width: 600px) {", "KEYWORD:@media|TYPE:max-width|NUMBER:600px"),
        Case("css", "@media (x) {\n  a { color: #fff }", "KEYWORD:@media|TYPE:x|TYPE:a|PROPERTY:color|NUMBER:#fff"),
        Case("css", "h1:hover { }", "TYPE:h1|TYPE:hover"),
        Case("css", "p {\n  background: url(a.png) !important;\n}", "TYPE:p|PROPERTY:background|FUNCTION:url|KEYWORD:!important"),
        Case("css", "p {\n  font-size: 1.5em;", "TYPE:p|PROPERTY:font-size|NUMBER:1.5em"),
        Case("css", "/* a\nb */ p {}", "COMMENT:/* a|COMMENT:b */|TYPE:p"),
        Case("css", "p {\n  content: \"x\\a\";", "TYPE:p|PROPERTY:content|STRING:\"x|ESCAPE:\\a|STRING:\""),
    )

    @Test fun `every language has at least 8 golden snippets`() {
        val per = cases.groupingBy { it.lang }.eachCount()
        for (l in Languages.ALL) assertEquals("${l.id} snippets >= 8", true, (per[l.id] ?: 0) >= 8)
        assertEquals(16, Languages.ALL.size)   // the 15 named languages, with C and C++ as separate lexers
    }

    @Test fun `golden tokens`() {
        val failures = ArrayList<String>()
        for (c in cases) {
            val got = lexAll(c.lang, c.src) { it != TokenCategory.PLAIN && it != TokenCategory.PUNCTUATION && it != TokenCategory.OPERATOR }
            if (got != c.expected) failures += "[${c.lang}] ${c.src.replace("\n", "\\n")}\n   expected: ${c.expected}\n   actual:   $got"
        }
        assertEquals("golden mismatches:\n" + failures.joinToString("\n"), 0, failures.size)
    }

    @Test fun `markdown emphasis is carried by style hints not colour`() {
        val l = Languages.byId("markdown").lexer
        val line = "a **bold** and *it* and ~~old~~ x"
        val sink = TokenSink(line.length)
        l.lexLine(line, LexState.INITIAL, sink)
        val styled = sink.build().filter { !it.style.isNone }.map { line.substring(it.start, it.end) to it.style }
        assertEquals(listOf("**bold**" to StyleHint.BOLD, "*it*" to StyleHint.ITALIC, "~~old~~" to StyleHint.STRIKE), styled)
    }

    @Test fun `unknown extensions fall back to plain text`() {
        assertEquals("plain", Languages.forFileName("notes.xyz").id)
        assertEquals("plain", Languages.forFileName("Makefile").id)
        assertEquals("plain", Languages.forFileName(".gitignore").id)
        val sink = TokenSink(12)
        assertEquals(LexState.INITIAL, Languages.PLAIN.lexer.lexLine("fun main() {", LexState.INITIAL, sink))
        assertEquals(listOf(Token(0, 12, TokenCategory.PLAIN, StyleHint.NONE)), sink.build())
    }

    @Test fun `file names map to languages`() {
        val m = mapOf(
            "a.kt" to "kotlin", "build.gradle.kts" to "gradle", "build.gradle" to "gradle", "settings.gradle.kts" to "gradle",
            "A.java" to "java", "x.tsx" to "javascript", "m.mjs" to "javascript", "s.py" to "python", "c.json" to "json",
            "d.yml" to "yaml", "Cargo.toml" to "toml", "R.md" to "markdown", "run.sh" to "shell", ".bashrc" to "shell",
            "m.c" to "c", "m.cpp" to "cpp", "lib.rs" to "rust", "main.go" to "go", "i.html" to "markup", "a.svg" to "markup", "s.css" to "css",
            "dir/sub/Main.KT" to "kotlin",
        )
        for ((f, id) in m) assertEquals(f, id, Languages.forFileName(f).id)
    }
}
