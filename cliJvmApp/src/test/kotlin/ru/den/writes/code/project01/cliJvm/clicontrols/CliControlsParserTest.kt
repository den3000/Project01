package ru.den.writes.code.project01.cliJvm.clicontrols

import ru.den.writes.code.project01.cliJvm.clicontrols.Surface.CMD
import ru.den.writes.code.project01.cliJvm.clicontrols.Surface.FLAG
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PROTOTYPE demo: parsing a single control off the shared catalog, identically for
 * the `-flag` and `/cmd` fronts. [ParsedControl.render] gives a compact
 * `token value [subs]` string the assertions read against.
 */
class CliControlsParserTest {

    private val parser = CliControlsParser()

    //region both fronts

    @Test
    fun `when the same control is given as flag and as command - then it parses identically`() {
        // when - then — one grammar, two prefixes
        assertEquals("profile work", ok("-profile work", FLAG).render())
        assertEquals("profile work", ok("/profile work", CMD).render())
    }

    @Test
    fun `when an entity is bare - then it is a list (no value, no subs)`() {
        // when
        val c = ok("/profile", CMD)

        // then
        assertNull(c.value)
        assertTrue(c.subs.isEmpty())
        assertEquals("profile", c.render())
    }
    //endregion

    //region entity ops

    @Test
    fun `when show or clean is used - then it parses as a sub with an optional name`() {
        // when - then
        assertEquals("profile [show]", ok("/profile show", CMD).render())
        assertEquals("profile [clean work]", ok("/profile clean work", CMD).render())
    }

    @Test
    fun `when a profile section is edited - then it nests name then section then text`() {
        // when
        val c = ok("/profile work style \"terse and short\"", CMD)

        // then — name is the value, section is a nested sub carrying the bullet text
        assertEquals("work", c.value)
        assertEquals("terse and short", c.sub(CliControlsArg.STYLE)?.value)
    }

    @Test
    fun `when a rule is added by text - then the text is the value (not a select)`() {
        // when - then
        assertEquals("rule always kotlin", ok("/rule \"always kotlin\"", CMD).render())
        assertEquals("rule [rm 003]", ok("/rule rm 003", CMD).render())
    }

    @Test
    fun `when a task op is used - then it nests under the task id`() {
        // when - then
        assertEquals("task auth [pause]", ok("/task auth pause", CMD).render())
        assertEquals("task auth [note did x]", ok("/task auth note \"did x\"", CMD).render())
    }
    //endregion

    //region agent (flat option bag)

    @Test
    fun `when an agent is configured - then its options parse as a flat list of subs`() {
        // when
        val c = ok("-agent main provider gemini model gemini-2.5-pro profile coder mode system stages execution..done", FLAG)

        // then
        assertEquals("main", c.value)
        assertEquals("gemini", c.sub(CliControlsArg.PROVIDER)?.value)
        assertEquals("gemini-2.5-pro", c.sub(CliControlsArg.MODEL)?.value)
        assertEquals("coder", c.sub(CliControlsArg.PROFILE)?.value)
        assertEquals("system", c.sub(CliControlsArg.MODE)?.value)
        assertEquals("execution..done", c.sub(CliControlsArg.STAGES)?.value)
    }

    @Test
    fun `when an agent is a judge - then the judge flag sub has no value`() {
        // when
        val c = ok("-agent checker judge stages execution..done", FLAG)

        // then
        assertTrue(c.sub(CliControlsArg.JUDGE) != null && c.sub(CliControlsArg.JUDGE)?.value == null)
        assertEquals("execution..done", c.sub(CliControlsArg.STAGES)?.value)
    }

    @Test
    fun `when a feed file is split by chunk - then chunkChars nests under feedFile`() {
        // when - then
        assertEquals("feedFile doc.txt [chunkChars 3000]", ok("-feedFile doc.txt chunkChars 3000", FLAG).render())
    }
    //endregion

    //region surface restrictions

    @Test
    fun `when a session name is given - then it is allowed at startup but not in-session`() {
        // when - then — select only at startup; in-session the bare form lists
        assertEquals("session demo", ok("-session demo", FLAG).render())
        assertEquals("session", ok("/session", CMD).render())
        assertTrue(err("/session demo", CMD) is ParseError.ValueNotAllowedHere)
    }

    @Test
    fun `when a command-only control is used as a flag - then wrong surface`() {
        // when - then
        assertTrue(err("-reuse", FLAG) is ParseError.WrongSurface)
        assertTrue(err("-branch exp", FLAG) is ParseError.WrongSurface)
        assertEquals("branch exp", ok("/branch exp", CMD).render())
    }

    @Test
    fun `when a startup-only control is used as a command - then wrong surface`() {
        // when - then
        assertTrue(err("/prompt hi", CMD) is ParseError.WrongSurface)
    }
    //endregion

    //region errors

    @Test
    fun `when the control is unknown - then UnknownControl`() {
        // when - then
        assertTrue(err("/nope", CMD) is ParseError.UnknownControl)
    }

    @Test
    fun `when a required value is missing - then MissingValue`() {
        // when - then
        assertTrue(err("-prompt", FLAG) is ParseError.MissingValue)
        assertTrue(err("/task auth note", CMD) is ParseError.MissingValue)
    }

    @Test
    fun `when a value fails its kind - then BadValue`() {
        // when - then — temperature out of 0..2, and a malformed stage range
        assertTrue(err("-agent x temperature 9", FLAG) is ParseError.BadValue)
        assertTrue(err("-agent x stages foo..bar", FLAG) is ParseError.BadValue)
    }

    @Test
    fun `when inflate is used - then it works on both fronts`() {
        // when - then
        assertEquals("inflate 5", ok("-inflate 5", FLAG).render())
        assertEquals("inflate 5", ok("/inflate 5", CMD).render())
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
    //endregion
}
