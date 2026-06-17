package ru.den.writes.code.project01.cliJvm.cliArgs

import ru.den.writes.code.project01.cliJvm.CliArgs
import ru.den.writes.code.project01.cliJvm.CliArgsException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CliArgsValidationTest {

    //region mode conflicts

    @Test
    fun `when -oneshot and -session both passed - then InvalidArgumentValue on -session`() {
        // given
        val args = arrayOf("-prompt", "hi", "-oneshot", "-session", "foo")

        // when
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parseCliArgsWithDummyKeys(*args)
        }

        // then
        assertEquals("-session", ex.argName)
    }

    @Test
    fun `when -oneshot used without -prompt - then MissingRequiredArgument on -prompt`() {
        // given
        val args = arrayOf("-oneshot")

        // when
        val ex = assertFailsWith<CliArgsException.MissingRequiredArgument> {
            parseCliArgsWithDummyKeys(*args)
        }

        // then
        assertEquals("-prompt", ex.argName)
    }

    @Test
    fun `when -sessions and -clean used together - then InvalidArgumentValue thrown`() {
        // given
        val args = arrayOf("-sessions", "-clean")

        // when - then
        assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parseCliArgsWithDummyKeys(*args)
        }
    }

    @Test
    fun `when -sessions and -oneshot used together - then InvalidArgumentValue thrown`() {
        // given
        val args = arrayOf("-sessions", "-oneshot", "-prompt", "x")

        // when - then
        assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parseCliArgsWithDummyKeys(*args)
        }
    }

    @Test
    fun `when -clean and -oneshot used together - then InvalidArgumentValue thrown`() {
        // given
        val args = arrayOf("-clean", "-oneshot", "-prompt", "x")

        // when - then
        assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parseCliArgsWithDummyKeys(*args)
        }
    }
    //endregion

    //region typed value parsing

    @Test
    fun `when -maxTokens value is not an integer - then InvalidArgumentValue on -maxTokens`() {
        // given
        val args = arrayOf("-prompt", "hi", "-maxTokens", "abc")

        // when
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parseCliArgsWithDummyKeys(*args)
        }

        // then
        assertEquals("-maxTokens", ex.argName)
    }

    @Test
    fun `when -temperature value is not a decimal - then InvalidArgumentValue on -temperature`() {
        // given
        val args = arrayOf("-prompt", "hi", "-temperature", "hot")

        // when
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parseCliArgsWithDummyKeys(*args)
        }

        // then
        assertEquals("-temperature", ex.argName)
    }

    @Test
    fun `when -stopSequence has more than max allowed - then TooManyValues thrown`() {
        // given
        val args = arrayOf("-prompt", "hi", "-stopSequence", "a", "b", "c", "d", "e", "f")

        // when
        val ex = assertFailsWith<CliArgsException.TooManyValues> {
            parseCliArgsWithDummyKeys(*args)
        }

        // then
        assertEquals("-stopSequence", ex.argName)
        assertEquals(6, ex.count)
        assertEquals(CliArgs.MAX_STOP_SEQUENCES, ex.maxAllowed)
    }
    //endregion

    //region session name rules

    @Test
    fun `when -session name contains whitespace - then InvalidArgumentValue on -session`() {
        // given
        val args = arrayOf("-prompt", "hi", "-session", "bad", "name", "with", "spaces")

        // when
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parseCliArgsWithDummyKeys(*args)
        }

        // then
        assertEquals("-session", ex.argName)
    }

    @Test
    fun `when -session name longer than 64 chars - then InvalidArgumentValue on -session`() {
        // given
        val longName = "a".repeat(65)
        val args = arrayOf("-prompt", "hi", "-session", longName)

        // when
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parseCliArgsWithDummyKeys(*args)
        }

        // then
        assertEquals("-session", ex.argName)
    }
    //endregion

    //region missing required

    @Test
    fun `when no args passed in default mode - then MissingRequiredArgument on -prompt`() {
        // given
        val args = emptyArray<String>()

        // when
        val ex = assertFailsWith<CliArgsException.MissingRequiredArgument> {
            parseCliArgsWithDummyKeys(*args)
        }

        // then
        assertEquals("-prompt", ex.argName)
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
