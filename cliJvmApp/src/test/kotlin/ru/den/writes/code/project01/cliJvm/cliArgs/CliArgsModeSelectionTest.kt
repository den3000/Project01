package ru.den.writes.code.project01.cliJvm.cliArgs

import ru.den.writes.code.project01.shared.llm.gemini.GeminiModel
import ru.den.writes.code.project01.shared.llm.ModelProvider
import ru.den.writes.code.project01.cliJvm.CliArgs
import ru.den.writes.code.project01.cliJvm.CliArgsException
import ru.den.writes.code.project01.cliJvm.ContextStrategyKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class CliArgsModeSelectionTest {

    //region admin commands

    @Test
    fun `when -sessions arg passed - then ListSessions returned`() {
        // given
        val args = arrayOf("-sessions")

        // when
        val actual = parseCliArgsWithDummyKeys(*args)

        // then
        val expected = CliArgs.ListSessions
        assertEquals(expected, actual)
    }

    @Test
    fun `when -sessions and -prompt both passed - then ListSessions wins`() {
        // given
        // Documented behaviour: in list mode every other flag is ignored.
        val args = arrayOf("-sessions", "-prompt", "ignored")

        // when
        val actual = parseCliArgsWithDummyKeys(*args)

        // then
        val expected = CliArgs.ListSessions
        assertEquals(expected, actual)
    }

    @Test
    fun `when -clean arg passed - then Clean returned`() {
        // given
        val args = arrayOf("-clean")

        // when
        val actual = parseCliArgsWithDummyKeys(*args)

        // then
        val expected = CliArgs.Clean
        assertEquals(expected, actual)
    }

    @Test
    fun `when admin command used with empty keys - then no API key required`() {
        // given
        // Read-only admin commands must work even without keys configured.

        // when
        val sessionsActual = CliArgs.from(arrayOf("-sessions"), geminiApiKey = "", openRouterApiKey = "")
        val cleanActual = CliArgs.from(arrayOf("-clean"), geminiApiKey = "", openRouterApiKey = "")

        // then
        assertEquals(CliArgs.ListSessions, sessionsActual)
        assertEquals(CliArgs.Clean, cleanActual)
    }
    //endregion

    //region chat mode

    @Test
    fun `when bare -prompt passed - then Chat with default Gemini and no knobs returned`() {
        // given
        val args = arrayOf("-prompt", "hi")

        // when
        val parsed = parseCliArgsWithDummyKeys(*args)

        // then
        val chat = assertIs<CliArgs.Chat>(parsed)
        assertEquals("hi", chat.prompt)
        assertNull(chat.maxTokens)
        assertNull(chat.stopSequences)
        assertNull(chat.endSequence)
        assertNull(chat.temperature)
        assertNull(chat.session)
        assertNull(chat.feedFile)
        assertEquals(2500, chat.chunkChars)
        assertEquals("", chat.feedInstruction)
        assertEquals(false, chat.byLine)
        assertEquals(false, chat.tui)
        assertEquals(ContextStrategyKind.FULL, chat.strategy)
        assertEquals(6, chat.keepLast)
        assertEquals(10, chat.summarizeEvery)
        val gemini = assertIs<ModelProvider.Gemini>(chat.modelProvider)
        assertEquals(GeminiModel.Default, gemini.model)
        assertEquals(DUMMY_GEMINI_KEY, gemini.apiKey)
    }

    @Test
    fun `when -prompt and all knobs passed - then Chat carries them all`() {
        // given
        val args = arrayOf(
            "-prompt", "hi",
            "-session", "foo",
            "-maxTokens", "100",
            "-temperature", "0.7",
            "-stopSequence", "stop", "halt",
            "-endSequence", "[END]",
            "-model", "gemini-2.5-flash",
        )

        // when
        val parsed = parseCliArgsWithDummyKeys(*args)

        // then
        val chat = assertIs<CliArgs.Chat>(parsed)
        assertEquals("hi", chat.prompt)
        assertEquals(100, chat.maxTokens)
        assertEquals(0.7, chat.temperature)
        assertEquals(listOf("stop", "halt"), chat.stopSequences)
        assertEquals("[END]", chat.endSequence)
        assertEquals("foo", chat.session)
        val gemini = assertIs<ModelProvider.Gemini>(chat.modelProvider)
        assertEquals(GeminiModel.Known.Gemini25Flash, gemini.model)
    }

    @Test
    fun `when -prompt has multiple words - then they are joined with spaces`() {
        // given
        val args = arrayOf("-prompt", "tell", "me", "a", "joke")

        // when
        val parsed = parseCliArgsWithDummyKeys(*args)

        // then
        val chat = assertIs<CliArgs.Chat>(parsed)
        assertEquals("tell me a joke", chat.prompt)
    }

    @Test
    fun `when -tui passed - then Chat opts into the TUI`() {
        // given
        val args = arrayOf("-prompt", "hi", "-tui")

        // when
        val parsed = parseCliArgsWithDummyKeys(*args)

        // then
        val chat = assertIs<CliArgs.Chat>(parsed)
        assertEquals(true, chat.tui)
    }

    @Test
    fun `when -tui combined with -oneshot - then rejected`() {
        // given — the TUI needs a REPL; oneshot has none
        val args = arrayOf("-prompt", "hi", "-oneshot", "-tui")

        // when - then
        assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parseCliArgsWithDummyKeys(*args)
        }
    }
    //endregion

    //region oneshot mode

    @Test
    fun `when -prompt and -oneshot passed - then OneShot returned`() {
        // given
        val args = arrayOf("-prompt", "hi", "-oneshot")

        // when
        val parsed = parseCliArgsWithDummyKeys(*args)

        // then
        val oneShot = assertIs<CliArgs.OneShot>(parsed)
        assertEquals("hi", oneShot.prompt)
    }

    @Test
    fun `when -oneshot with generation knobs - then they are carried through`() {
        // given
        val args = arrayOf(
            "-prompt", "hi",
            "-oneshot",
            "-maxTokens", "42",
            "-temperature", "0.5",
            "-model", "gemini-2.5-flash",
        )

        // when
        val parsed = parseCliArgsWithDummyKeys(*args)

        // then
        val oneShot = assertIs<CliArgs.OneShot>(parsed)
        assertEquals(42, oneShot.maxTokens)
        assertEquals(0.5, oneShot.temperature)
        val gemini = assertIs<ModelProvider.Gemini>(oneShot.modelProvider)
        assertEquals(GeminiModel.Known.Gemini25Flash, gemini.model)
    }
    //endregion

    private fun parseCliArgsWithDummyKeys(vararg args: String): CliArgs =
        CliArgs.from(
            args = arrayOf(*args),
            geminiApiKey = DUMMY_GEMINI_KEY,
            openRouterApiKey = DUMMY_OPENROUTER_KEY,
            huggingFaceApiKey = DUMMY_HUGGINGFACE_KEY,
        )

    private companion object {
        const val DUMMY_GEMINI_KEY = "test-gemini-key"
        const val DUMMY_OPENROUTER_KEY = "test-openrouter-key"
        const val DUMMY_HUGGINGFACE_KEY = "test-huggingface-key"
    }
}
