package dev.fonebrew.domain.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolProtocolTest {

    @Test fun `parses a call with arguments`() {
        val t = ToolCallParser.parse("thinking\n<<<TOOL read_file>>>\npath: src/A.kt\nstart: 3\n<<<END>>>\n")
        assertTrue(t.errors.isEmpty())
        val c = t.calls.single()
        assertEquals("read_file", c.name)
        assertEquals(mapOf("path" to "src/A.kt", "start" to "3"), c.args)
        assertNull(c.body)
    }

    @Test fun `body is everything after the first separator, including later dashes`() {
        val t = ToolCallParser.parse("<<<TOOL propose_change>>>\npath: a.md\n---\nTitle\n---\nmore\n<<<END>>>")
        assertEquals("Title\n---\nmore", t.calls.single().body)
    }

    @Test fun `several blocks in one reply keep their order`() {
        val t = ToolCallParser.parse("<<<TOOL list_dir>>>\npath: a\n<<<END>>>\n<<<TOOL search>>>\nquery: foo\n<<<END>>>")
        assertEquals(listOf("list_dir", "search"), t.calls.map { it.name })
    }

    @Test fun `an unterminated block is an error and nothing is parsed from it`() {
        val t = ToolCallParser.parse("<<<TOOL propose_change>>>\npath: a.kt\n---\nhalf a fi")
        assertTrue(t.calls.isEmpty())
        assertEquals(1, t.errors.size)
        assertTrue(t.errors[0].message.contains("never closed"))
    }

    @Test fun `a bad header does not swallow the next block`() {
        val t = ToolCallParser.parse("<<<TOOL bad name!>>>\nx: y\n<<<END>>>\n<<<TOOL list_dir>>>\n<<<END>>>")
        assertEquals(1, t.errors.size)
        assertEquals(listOf("list_dir"), t.calls.map { it.name })
    }

    @Test fun `bad argument lines and duplicates are errors`() {
        assertEquals(1, ToolCallParser.parse("<<<TOOL x>>>\nno colon here\n<<<END>>>").errors.size)
        assertEquals(1, ToolCallParser.parse("<<<TOOL x>>>\na: 1\na: 2\n<<<END>>>").errors.size)
    }

    @Test fun `text with no block parses to nothing and never throws`() {
        assertTrue(ToolCallParser.parse("just chatting").isEmpty)
        assertTrue(ToolCallParser.parse("").isEmpty)
        ToolCallParser.parse("<<<TOOL")
        ToolCallParser.parse("<<<TOOL a>>>\n<<<TOOL b>>>\n<<<END>>>")
    }

    private val reg = ToolRegistry().register(
        ToolSpec("t", SideEffect.READ_LOCAL, "d", listOf(ParamSpec("p"), ParamSpec("n", ParamType.INT, required = false))) { ToolResult.ok("ran") },
    ).register(ToolSpec("b", SideEffect.READ_LOCAL, "d", takesBody = true) { ToolResult.ok("ran") })

    private fun call(name: String, args: Map<String, String> = emptyMap(), body: String? = null) = RawCall(0, name, args, body)

    @Test fun `registry validation is typed and total`() {
        assertTrue(reg.validate(call("zzz")) is ToolRegistry.Validated.Bad)
        assertTrue(reg.validate(call("t")) is ToolRegistry.Validated.Bad) // missing p
        assertTrue(reg.validate(call("t", mapOf("p" to "x", "q" to "1"))) is ToolRegistry.Validated.Bad) // unknown arg
        assertTrue(reg.validate(call("t", mapOf("p" to "x", "n" to "abc"))) is ToolRegistry.Validated.Bad)
        assertTrue(reg.validate(call("t", mapOf("p" to "x"), body = "no")) is ToolRegistry.Validated.Bad)
        assertTrue(reg.validate(call("b")) is ToolRegistry.Validated.Bad) // body required
        val ok = reg.validate(call("t", mapOf("p" to "x", "n" to "5"))) as ToolRegistry.Validated.Ok
        assertEquals(5, ok.args.int("n"))
    }

    // ---- 6b: the extension point ---------------------------------------------------------

    private class Script(private vararg val replies: String) : AgentTurnSource {
        var i = 0
        override suspend fun next(messages: List<AgentMessage>, limits: TurnLimits) = TurnReply(replies[minOf(i++, replies.size - 1)])
    }

    private val callWrite = "<<<TOOL write_scratch>>>\ntext: hi\n<<<END>>>"
    private val finish = "<<<TOOL finish>>>\nsummary: done\n<<<END>>>"

    private fun session(ran: MutableList<String>, gate: ConsentGate?, vararg replies: String): Pair<AgentSession, AgentWorkspace> {
        val ws = AgentWorkspace(FakeRepo(emptyMap()))
        val registry = BuiltinTools.registry(ws).register(
            ToolSpec("write_scratch", SideEffect.WRITE_LOCAL, "fake later-package tool", listOf(ParamSpec("text"))) { ran += it.text("text"); ToolResult.ok("wrote") },
        )
        val s = if (gate == null) AgentSession(registry, Script(*replies)) else AgentSession(registry, Script(*replies), gate = gate)
        return s to ws
    }

    @Test fun `a registered WRITE_LOCAL tool is refused without explicit per-call consent`() = runTest {
        val ran = mutableListOf<String>()
        val (s, ws) = session(ran, null, callWrite, finish)
        val out = s.run("o", emptyMap(), ws)
        assertTrue(ran.isEmpty())
        val c = out.steps[0].calls.single()
        assertEquals(CallStatus.REFUSED, c.status)
        assertEquals(StopReason.FINISHED, out.stop)
    }

    @Test fun `an approving gate runs it, and is asked again for every call`() = runTest {
        val ran = mutableListOf<String>()
        val asked = mutableListOf<ConsentRequest>()
        val (s, ws) = session(ran, ConsentGate { asked += it; true }, callWrite, callWrite, finish)
        s.run("o", emptyMap(), ws)
        assertEquals(listOf("hi", "hi"), ran)
        assertEquals(2, asked.size)
        assertEquals(SideEffect.WRITE_LOCAL, asked[0].effect)
    }

    @Test fun `a gate that says no keeps it from running, and DESTRUCTIVE is denied without asking`() = runTest {
        val ran = mutableListOf<String>()
        val (s, ws) = session(ran, ConsentGate { false }, callWrite, finish)
        s.run("o", emptyMap(), ws)
        assertTrue(ran.isEmpty())

        val ws2 = AgentWorkspace(FakeRepo(emptyMap()))
        var asked = false
        val reg2 = BuiltinTools.registry(ws2).register(
            ToolSpec("rm_all", SideEffect.DESTRUCTIVE, "x") { ran += "rm"; ToolResult.ok("gone") },
        )
        AgentSession(reg2, Script("<<<TOOL rm_all>>>\n<<<END>>>", finish), gate = ConsentGate { asked = true; true }).run("o", emptyMap(), ws2)
        assertFalse(asked)
        assertFalse("rm" in ran)
    }
}
