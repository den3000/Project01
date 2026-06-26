package ru.den.writes.code.project01.cliJvm.command

import ru.den.writes.code.project01.cliJvm.CliArgsException
import ru.den.writes.code.project01.cliJvm.ContextStrategyKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** CliControls front: RunChat field extraction + legacy defaults. */
class CliControlsCommandParserFieldsTest {

    private val parser = CliControlsCommandParser(
        ApiKeys(DUMMY_GEMINI_KEY, DUMMY_OPENROUTER_KEY, DUMMY_HUGGINGFACE_KEY),
    )

    @Test
    fun `when a feed file is configured - then its subs land on the command`() {
        // when
        val chat = chat("-prompt", "hi", "-feedFile", "/tmp/x", "chunkChars", "999", "feedInstruction", "go go")

        // then
        assertEquals("/tmp/x", chat.feedFile)
        assertEquals(999, chat.chunkChars)
        assertEquals("go go", chat.feedInstruction)
    }

    @Test
    fun `when a feed file is split by line - then byLine is set`() {
        // when
        val chat = chat("-prompt", "hi", "-feedFile", "/tmp/x", "byLine")

        // then
        assertTrue(chat.byLine)
    }

    @Test
    fun `when a window strategy with keepLast is given - then both land`() {
        // when
        val chat = chat("-prompt", "hi", "-strategy", "window", "keepLast", "4")

        // then
        assertEquals(ContextStrategyKind.WINDOW, chat.strategy)
        assertEquals(4, chat.keepLast)
    }

    @Test
    fun `when a summary strategy with summarizeEvery is given - then both land`() {
        // when
        val chat = chat("-prompt", "hi", "-strategy", "summary", "summarizeEvery", "20")

        // then
        assertEquals(ContextStrategyKind.SUMMARY, chat.strategy)
        assertEquals(20, chat.summarizeEvery)
    }

    @Test
    fun `when more than the max stop sequences are given - then TooManyValues`() {
        // when
        val ex = assertFailsWith<CliArgsException.TooManyValues> {
            parser.parse(arrayOf("-prompt", "hi", "-agent", "m", "stopSequence", "a b c d e f"))
        }

        // then
        assertEquals(6, ex.count)
        assertEquals(5, ex.maxAllowed)
    }

    @Test
    fun `when session, tui and mcpServer are given - then all land on the command`() {
        // when
        val chat = chat("-prompt", "hi", "-session", "foo", "-tui", "-mcpServer", "mcpLab --serve")

        // then
        assertEquals("foo", chat.session)
        assertTrue(chat.tui)
        assertEquals("mcpLab --serve", chat.mcpServer)
    }

    @Test
    fun `when collect and agent tasks are scheduled - then both land as schedule specs`() {
        // when — collect needs an MCP server; two repeatable -schedule groups
        val chat = chat(
            "-prompt", "hi", "-mcpServer", "mcpLab --serve",
            "-schedule", "collect", "tool", "current_weather", "args", "{\"city\":\"Paris\"}", "every", "30",
            "-schedule", "agent", "prompt", "daily digest", "after", "60",
        )

        // then
        assertEquals(
            listOf(
                ScheduleSpec.Collect(tool = "current_weather", args = "{\"city\":\"Paris\"}", seconds = 30, periodic = true),
                ScheduleSpec.Agent(prompt = "daily digest", seconds = 60, periodic = false),
            ),
            chat.schedules,
        )
    }

    @Test
    fun `when a collect task is scheduled without an mcp server - then it requires one`() {
        // when - then
        assertFailsWith<CliArgsException> {
            parser.parse(arrayOf("-prompt", "hi", "-schedule", "collect", "tool", "current_weather", "every", "30"))
        }
    }

    private fun chat(vararg args: String): CliCommand.RunChat = assertIs<CliCommand.RunChat>(parser.parse(arrayOf(*args)))

    private companion object {
        const val DUMMY_GEMINI_KEY = "test-gemini-key"
        const val DUMMY_OPENROUTER_KEY = "test-openrouter-key"
        const val DUMMY_HUGGINGFACE_KEY = "test-huggingface-key"
    }
}
