package dev.fonebrew.watchbridge

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The platform-independent RFC 8259 gate that runs before org.json sees any text. */
class StrictJsonTest {

    private fun accepts(text: String) {
        try {
            StrictJson.requireSingleObject(text)
        } catch (e: WireException) {
            fail("should accept <$text> but: ${e.message}")
        }
    }

    private fun rejects(text: String) {
        try {
            StrictJson.requireSingleObject(text)
        } catch (e: WireException) {
            assertTrue("rejection must explain itself", !e.message.isNullOrBlank())
            return
        }
        fail("should reject <$text>")
    }

    @Test
    fun accepts_validObjects() {
        accepts("{}")
        accepts(" \t\r\n{ } \n")
        accepts("""{"a":1}""")
        accepts("""{"a":-0,"b":0,"c":10,"d":-12.5,"e":1e5,"f":1E-5,"g":2.5e+10,"h":0.0}""")
        accepts("""{"a":true,"b":false,"c":null}""")
        accepts("""{"a":[],"b":[1,2,3],"c":[{"x":[[]]}],"d":{}}""")
        accepts("""{"s":"quote \" backslash \\ slash \/ \b \f \n \r \t \u00e9 \uD83D\uDE42 \u0000"}""")
        accepts("{\"s\":\"raw non-ascii é 日本語 🙂 and U+2028 \u2028\"}")
        accepts("""{"a" : 1 , "b" : [ 1 , 2 ] }""")
    }

    @Test
    fun rejects_nonObjectsAndEmpties() {
        for (t in listOf("", " ", "[]", "[{}]", "1", "\"s\"", "true", "null", "x", "{", "}", "{}}", "{}{}", "{} x", "x {}", "{},")) rejects(t)
    }

    @Test
    fun rejects_syntaxErrors() {
        for (
            t in listOf(
                """{"a":}""", """{"a"}""", """{"a":1,}""", """{,"a":1}""", """{"a":1 "b":2}""", """{"a" 1}""", """{a:1}""", """{'a':1}""",
                """{"a":[1,]}""", """{"a":[,1]}""", """{"a":[1 2]}""", """{"a":[}""", """{"a":{}""", """{"a":1}}""",
                """{"a":"unterminated}""", """{"a":"bad \x escape"}""", """{"a":"bad \u12G4"}""", """{"a":"short \u12"}""",
                "{\"a\":\"raw\ttab\"}", "{\"a\":\"raw\nnewline\"}", "{\"a\":\"raw\u0001ctrl\"}",
                """{"a":tru}""", """{"a":True}""", """{"a":nul}""", """{"a":nullx}""", """{"a":NaN}""", """{"a":Infinity}""", """{"a":-Infinity}""",
                """{"a":01}""", """{"a":+1}""", """{"a":.5}""", """{"a":1.}""", """{"a":-}""", """{"a":1e}""", """{"a":1e+}""", """{"a":0x10}""", """{"a":--1}""",
                """{"a":1} // c""", """{/*c*/"a":1}""", """{"a":1}#c""",
            )
        ) rejects(t)
        // Fullwidth digits after backslash-u: Character.digit() would accept them; JSON hex is ASCII only.
        rejects("{\"a\":\"fullwidth \\u１１１１\"}")
    }

    @Test
    fun rejects_duplicateKeys_evenWhenEscaped() {
        rejects("""{"a":1,"a":2}""")
        rejects("""{"a":1,"\u0061":2}""")
        rejects("""{"o":{"x":1,"x":2}}""")
        accepts("""{"a":1,"o":{"a":2}}""") // same key at different levels is fine
        accepts("""{"a":{"x":1},"b":{"x":2}}""")
    }

    @Test
    fun depthIsBounded_andHostileNestingDoesNotOverflowTheStack() {
        fun nested(depth: Int) = "{\"a\":".repeat(depth) + "1" + "}".repeat(depth)
        accepts(nested(StrictJson.MAX_DEPTH))
        rejects(nested(StrictJson.MAX_DEPTH + 1))
        rejects("{\"a\":" + "[".repeat(1_000_000))
        rejects("{\"a\":" + "[".repeat(100_000) + "]".repeat(100_000) + "}")
    }
}
