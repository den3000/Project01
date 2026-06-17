package ru.den.writes.code.project01.cliJvm.cliArgs

import ru.den.writes.code.project01.cliJvm.CliArgs
import ru.den.writes.code.project01.cliJvm.CliArgsException
import ru.den.writes.code.project01.cliJvm.ContextStrategyKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class CliArgsStrategyTest {

    //region -strategy selection

    @Test
    fun `when -strategy window passed - then WINDOW selected`() {
        // given
        val args = arrayOf("-prompt", "hi", "-strategy", "window")

        // when
        val parsed = parseCliArgsWithDummyKeys(*args)

        // then
        val chat = assertIs<CliArgs.Chat>(parsed)
        assertEquals(ContextStrategyKind.WINDOW, chat.strategy)
    }

    @Test
    fun `when -strategy summary passed - then SUMMARY selected`() {
        // given
        val args = arrayOf("-prompt", "hi", "-strategy", "summary")

        // when
        val parsed = parseCliArgsWithDummyKeys(*args)

        // then
        val chat = assertIs<CliArgs.Chat>(parsed)
        assertEquals(ContextStrategyKind.SUMMARY, chat.strategy)
    }

    @Test
    fun `when -strategy facts passed - then FACTS selected`() {
        // given
        val args = arrayOf("-prompt", "hi", "-strategy", "facts")

        // when
        val parsed = parseCliArgsWithDummyKeys(*args)

        // then
        val chat = assertIs<CliArgs.Chat>(parsed)
        assertEquals(ContextStrategyKind.FACTS, chat.strategy)
    }

    @Test
    fun `when no strategy flag passed or full passed explicitly - then FULL selected`() {
        // given
        val defaultArgs = arrayOf("-prompt", "hi")
        val explicitArgs = arrayOf("-prompt", "hi", "-strategy", "full")

        // when
        val defaultChat = assertIs<CliArgs.Chat>(parseCliArgsWithDummyKeys(*defaultArgs))
        val explicitChat = assertIs<CliArgs.Chat>(parseCliArgsWithDummyKeys(*explicitArgs))

        // then
        assertEquals(ContextStrategyKind.FULL, defaultChat.strategy)
        assertEquals(ContextStrategyKind.FULL, explicitChat.strategy)
    }

    @Test
    fun `when -strategy has unknown value - then InvalidArgumentValue on -strategy`() {
        // given
        val args = arrayOf("-prompt", "hi", "-strategy", "bogus")

        // when
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parseCliArgsWithDummyKeys(*args)
        }

        // then
        assertEquals("-strategy", ex.argName)
    }
    //endregion

    //region tuning flags under -strategy facts

    @Test
    fun `when -keepLast used under -strategy facts - then accepted and carried through`() {
        // given
        val args = arrayOf("-prompt", "hi", "-strategy", "facts", "-keepLast", "4")

        // when
        val parsed = parseCliArgsWithDummyKeys(*args)

        // then
        val chat = assertIs<CliArgs.Chat>(parsed)
        assertEquals(ContextStrategyKind.FACTS, chat.strategy)
        assertEquals(4, chat.keepLast)
    }

    @Test
    fun `when -summarizeEvery used under -strategy facts - then InvalidArgumentValue on -summarizeEvery`() {
        // given
        val args = arrayOf("-prompt", "hi", "-strategy", "facts", "-summarizeEvery", "5")

        // when
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parseCliArgsWithDummyKeys(*args)
        }

        // then
        assertEquals("-summarizeEvery", ex.argName)
    }
    //endregion

    //region tuning flags under -strategy window

    @Test
    fun `when -keepLast used under -strategy window - then accepted and carried through`() {
        // given
        val args = arrayOf("-prompt", "hi", "-strategy", "window", "-keepLast", "4")

        // when
        val parsed = parseCliArgsWithDummyKeys(*args)

        // then
        val chat = assertIs<CliArgs.Chat>(parsed)
        assertEquals(ContextStrategyKind.WINDOW, chat.strategy)
        assertEquals(4, chat.keepLast)
    }

    @Test
    fun `when -summarizeEvery used under -strategy window - then InvalidArgumentValue on -summarizeEvery`() {
        // given
        val args = arrayOf("-prompt", "hi", "-strategy", "window", "-summarizeEvery", "5")

        // when
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parseCliArgsWithDummyKeys(*args)
        }

        // then
        assertEquals("-summarizeEvery", ex.argName)
    }
    //endregion

    //region -compress as shorthand for -strategy summary

    @Test
    fun `when -compress passed alone or paired with -strategy summary - then SUMMARY selected`() {
        // given
        val aloneArgs = arrayOf("-prompt", "hi", "-compress")
        val pairedArgs = arrayOf("-prompt", "hi", "-compress", "-strategy", "summary")

        // when
        val aloneChat = assertIs<CliArgs.Chat>(parseCliArgsWithDummyKeys(*aloneArgs))
        val pairedChat = assertIs<CliArgs.Chat>(parseCliArgsWithDummyKeys(*pairedArgs))

        // then
        assertEquals(ContextStrategyKind.SUMMARY, aloneChat.strategy)
        assertEquals(ContextStrategyKind.SUMMARY, pairedChat.strategy)
    }

    @Test
    fun `when -compress paired with a conflicting strategy - then InvalidArgumentValue on -compress`() {
        // given
        val args = arrayOf("-prompt", "hi", "-compress", "-strategy", "window")

        // when
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parseCliArgsWithDummyKeys(*args)
        }

        // then
        assertEquals("-compress", ex.argName)
    }
    //endregion

    //region incompatible modes

    @Test
    fun `when -strategy combined with -oneshot - then InvalidArgumentValue on -strategy`() {
        // given
        val args = arrayOf("-prompt", "hi", "-oneshot", "-strategy", "window")

        // when
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parseCliArgsWithDummyKeys(*args)
        }

        // then
        assertEquals("-strategy", ex.argName)
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
