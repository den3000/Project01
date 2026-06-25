package ru.den.writes.code.project01.cliJvm.clicontrols

import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.AGENT
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.BRANCH
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.CHECK
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.CHUNK_CHARS
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.CLEAN
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.FEED_FILE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.INFLATE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.JUDGE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.MCP_SERVER
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.MEMORY
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.MEMORY_MODE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.MODE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.MODEL
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.NOTE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.PAUSE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.PROFILE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.PROMPT
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.PROVIDER
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.RM
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.RULE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.SESSION
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.SHOW
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.STAGES
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.STYLE
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.SWITCH
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.TASK
import ru.den.writes.code.project01.cliJvm.clicontrols.CliControlsArg.TEMPERATURE
import ru.den.writes.code.project01.cliJvm.clicontrols.Surface.CMD
import ru.den.writes.code.project01.cliJvm.clicontrols.Surface.FLAG
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PROTOTYPE demo: parsing a single control off the shared catalog, identically for
 * the `-flag` and `/cmd` fronts. Each assertion pins the exact [ParsedControl]
 * (built off the same catalog) or the exact [ParseError].
 */
class CliControlsParserTest {

    private val parser = CliControlsParser()

    //region both fronts

    @Test
    fun `when the same control is given as flag and as command - then it parses identically`() {
        // given — one grammar, two prefixes → the same parsed control
        val expected = topParsedControl(PROFILE, FLAG, "work")

        // when - then
        assertEquals(expected, ok("-profile work", FLAG))
        assertEquals(expected, ok("/profile work", CMD))
    }

    @Test
    fun `when an entity is bare - then it is a list (no value, no subs)`() {
        // when
        val actual = ok("/profile", CMD)

        // then
        assertEquals(topParsedControl(PROFILE, CMD), actual)
    }
    //endregion

    //region entity ops

    @Test
    fun `when show or clean is used - then it parses as a sub with an optional name`() {
        // when - then
        assertEquals(topParsedControl(PROFILE, CMD, subs = listOf(subParsedControl(PROFILE, SHOW))), ok("/profile show", CMD))
        assertEquals(topParsedControl(PROFILE, CMD, subs = listOf(subParsedControl(PROFILE, CLEAN, "work"))), ok("/profile clean work", CMD))
    }

    @Test
    fun `when a profile section is edited - then it nests name then section then text`() {
        // given — name is the value, section is a nested sub carrying the bullet text
        val expected = topParsedControl(PROFILE, CMD, "work", listOf(subParsedControl(PROFILE, STYLE, "terse and short")))

        // when - then
        assertEquals(expected, ok("/profile work style \"terse and short\"", CMD))
    }

    @Test
    fun `when a rule is added by text - then the text is the value (not a select)`() {
        // when - then
        assertEquals(topParsedControl(RULE, CMD, "always kotlin"), ok("/rule \"always kotlin\"", CMD))
        assertEquals(topParsedControl(RULE, CMD, subs = listOf(subParsedControl(RULE, RM, "003"))), ok("/rule rm 003", CMD))
    }

    @Test
    fun `when a task op is used - then it nests under the task id`() {
        // when - then
        assertEquals(topParsedControl(TASK, CMD, "auth", listOf(subParsedControl(TASK, PAUSE))), ok("/task auth pause", CMD))
        assertEquals(topParsedControl(TASK, CMD, "auth", listOf(subParsedControl(TASK, NOTE, "did x"))), ok("/task auth note \"did x\"", CMD))
    }

    @Test
    fun `when a branch is switched or checkpointed - then it nests as a sub`() {
        // when - then — check is bare (old /checkpoint), switch carries the target name
        assertEquals(topParsedControl(BRANCH, CMD, subs = listOf(subParsedControl(BRANCH, CHECK))), ok("/branch check", CMD))
        assertEquals(topParsedControl(BRANCH, CMD, subs = listOf(subParsedControl(BRANCH, SWITCH, "exp"))), ok("/branch switch exp", CMD))
    }
    //endregion

    //region agent (flat option bag)

    @Test
    fun `when an agent is configured - then its options parse as a flat list of subs`() {
        // given
        val expected = topParsedControl(
            AGENT, FLAG, "main",
            listOf(
                subParsedControl(AGENT, PROVIDER, "gemini"),
                subParsedControl(AGENT, MODEL, "gemini-2.5-pro"),
                subParsedControl(AGENT, PROFILE, "coder"),
                subParsedControl(AGENT, MODE, "system"),
                subParsedControl(AGENT, STAGES, "execution..done"),
            ),
        )

        // when - then
        assertEquals(expected, ok("-agent main provider gemini model gemini-2.5-pro profile coder mode system stages execution..done", FLAG))
    }

    @Test
    fun `when an agent is a judge - then the judge flag sub has no value`() {
        // given — judge is a bare sub (no value)
        val expected = topParsedControl(
            AGENT, FLAG, "checker",
            listOf(
                subParsedControl(AGENT, JUDGE),
                subParsedControl(AGENT, STAGES, "execution..done"),
            ),
        )

        // when - then
        assertEquals(expected, ok("-agent checker judge stages execution..done", FLAG))
    }

    @Test
    fun `when a feed file is split by chunk - then chunkChars nests under feedFile`() {
        // given
        val expected = topParsedControl(FEED_FILE, FLAG, "doc.txt", listOf(subParsedControl(FEED_FILE, CHUNK_CHARS, "3000")))

        // when - then
        assertEquals(expected, ok("-feedFile doc.txt chunkChars 3000", FLAG))
    }
    //endregion

    //region surface restrictions

    @Test
    fun `when a session name is given - then it is allowed at startup but not in-session`() {
        // when - then — select only at startup; in-session the bare form lists
        assertEquals(topParsedControl(SESSION, FLAG, "demo"), ok("-session demo", FLAG))
        assertEquals(topParsedControl(SESSION, CMD), ok("/session", CMD))
        assertEquals(ParseError.ValueNotAllowedHere(SESSION, CMD), err("/session demo", CMD))
    }

    @Test
    fun `when a command-only control is used as a flag - then wrong surface`() {
        // when - then
        assertEquals(ParseError.WrongSurface("reuse", FLAG), err("-reuse", FLAG))
        assertEquals(ParseError.WrongSurface("branch", FLAG), err("-branch exp", FLAG))
        assertEquals(ParseError.WrongSurface("memory", FLAG), err("-memory", FLAG))
        assertEquals(topParsedControl(BRANCH, CMD, "exp"), ok("/branch exp", CMD))
    }

    @Test
    fun `when a startup-only control is used as a command - then wrong surface`() {
        // when - then
        assertEquals(ParseError.WrongSurface("prompt", CMD), err("/prompt hi", CMD))
    }
    //endregion

    //region in-session config commands

    @Test
    fun `when an in-session memory command is used - then it parses on the command front`() {
        // when - then — show the active layer; flip the injection mode
        assertEquals(topParsedControl(MEMORY, CMD), ok("/memory", CMD))
        assertEquals(topParsedControl(MEMORY_MODE, CMD, "system"), ok("/memory-mode system", CMD))
    }
    //endregion

    //region errors

    @Test
    fun `when the control is unknown - then UnknownControl`() {
        // when - then
        assertEquals(ParseError.UnknownControl("nope"), err("/nope", CMD))
    }

    @Test
    fun `when a required value is missing - then MissingValue`() {
        // when - then
        assertEquals(ParseError.MissingValue(PROMPT), err("-prompt", FLAG))
        assertEquals(ParseError.MissingValue(NOTE), err("/task auth note", CMD))
        assertEquals(ParseError.MissingValue(MEMORY_MODE), err("/memory-mode", CMD))
    }

    @Test
    fun `when a value fails its kind - then BadValue`() {
        // when - then — temperature out of 0..2, and a malformed stage range
        assertEquals(ParseError.BadValue(TEMPERATURE, "9", "a number in 0.0..2.0"), err("-agent x temperature 9", FLAG))
        assertEquals(ParseError.BadValue(STAGES, "foo..bar", "a stage range like clarification..planning"), err("-agent x stages foo..bar", FLAG))
        assertEquals(ParseError.BadValue(MEMORY_MODE, "loud", "one of: preamble, system"), err("/memory-mode loud", CMD))
    }

    @Test
    fun `when inflate is used - then it works on both fronts`() {
        // when - then
        assertEquals(topParsedControl(INFLATE, FLAG, "5"), ok("-inflate 5", FLAG))
        assertEquals(topParsedControl(INFLATE, CMD, "5"), ok("/inflate 5", CMD))
    }

    @Test
    fun `when an mcp server is given - then both fronts keep the quoted command as one value`() {
        // when - then
        assertEquals(topParsedControl(MCP_SERVER, FLAG, "mcpLab --serve"), ok("-mcpServer \"mcpLab --serve\"", FLAG))
        assertEquals(topParsedControl(MCP_SERVER, CMD, "mcpLab --serve"), ok("/mcpServer \"mcpLab --serve\"", CMD))
    }
    //endregion

    //region helpers

    private fun ok(line: String, surface: Surface): ParsedControl {
        val r = parser.parse(line, surface)
        assertTrue(r is ParseResult.Ok, "expected Ok for '$line', got $r")
        return (r as ParseResult.Ok).control
    }

    private fun err(line: String, surface: Surface): ParseError {
        val r = parser.parse(line, surface)
        assertTrue(r is ParseResult.Err, "expected Err for '$line', got $r")
        return (r as ParseResult.Err).error
    }

    private fun topParsedControl(
        arg: CliControlsArg,
        surface: Surface,
        value: String? = null,
        subs: List<ParsedControl> = emptyList(),
    ): ParsedControl = ParsedControl(requireNotNull(CliControls.topLevel(arg, surface)), value, subs)

    private fun subParsedControl(
        parent: CliControlsArg,
        arg: CliControlsArg,
        value: String? = null,
        subs: List<ParsedControl> = emptyList(),
    ): ParsedControl = ParsedControl(requireNotNull(CliControls.subOf(listOf(parent), arg.title)), value, subs)
    //endregion
}
