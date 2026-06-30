package ru.den.writes.code.project01.cliJvm.command

import ru.den.writes.code.project01.cliJvm.CliArgsException
import ru.den.writes.code.project01.cliJvm.ContextStrategyKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** ControlsToCommand: RunChat field extraction + legacy defaults. */
class ControlsToCommandFieldsTest {

    @Test
    fun `when a feed file is configured - then its subs land on the command`() {
        // given
        val parser = createCommandsParser()
        val input = "-prompt hi -feedFile /tmp/x chunkChars 999 feedInstruction \"go go\""

        // when
        val actual = parser.parse(input.toArgsArray())

        // then
        assertIs<CliCommand.RunChat>(actual)
        assertEquals("/tmp/x", actual.feedFile)
        assertEquals(999, actual.chunkChars)
        assertEquals("go go", actual.feedInstruction)
    }

    @Test
    fun `when a feed file is split by line - then byLine is set`() {
        // given
        val parser = createCommandsParser()
        val input = "-prompt hi -feedFile /tmp/x byLine"

        // when
        val actual = parser.parse(input.toArgsArray())

        // then
        assertIs<CliCommand.RunChat>(actual)
        assertTrue(actual.byLine)
    }

    @Test
    fun `when a window strategy with keepLast is given - then both land`() {
        // given
        val parser = createCommandsParser()
        val input = "-prompt hi -strategy window keepLast 4"

        // when
        val actual = parser.parse(input.toArgsArray())

        // then
        assertIs<CliCommand.RunChat>(actual)
        assertEquals(ContextStrategyKind.WINDOW, actual.strategy)
        assertEquals(4, actual.keepLast)
    }

    @Test
    fun `when a summary strategy with summarizeEvery is given - then both land`() {
        // given
        val parser = createCommandsParser()
        val input = "-prompt hi -strategy summary summarizeEvery 20"

        // when
        val actual = parser.parse(input.toArgsArray())

        // then
        assertIs<CliCommand.RunChat>(actual)
        assertEquals(ContextStrategyKind.SUMMARY, actual.strategy)
        assertEquals(20, actual.summarizeEvery)
    }

    @Test
    fun `when more than the max stop sequences are given - then TooManyValues`() {
        // given
        val parser = createCommandsParser()
        val input = "-prompt hi -agent m stopSequence \"a b c d e f\""

        // when
        val ex = assertFailsWith<CliArgsException.TooManyValues> { parser.parse(input.toArgsArray()) }

        // then
        assertEquals(6, ex.count)
        assertEquals(5, ex.maxAllowed)
    }

    @Test
    fun `when session, tui and mcpServer are given - then all land on the command`() {
        // given
        val parser = createCommandsParser()
        val input = "-prompt hi -session foo -tui -mcpServer \"mcpLab --serve\""

        // when
        val actual = parser.parse(input.toArgsArray())

        // then
        assertIs<CliCommand.RunChat>(actual)
        assertEquals("foo", actual.session)
        assertTrue(actual.tui)
        assertEquals("mcpLab --serve", actual.mcpServer)
    }
}
