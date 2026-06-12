package ru.den.writes.code.project01.cliJvm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CliArgsTest {

    // Non-blank dummy keys: every PromptCommand path now requires the
    // chosen provider's API key to be non-blank, so tests that don't
    // care about key validation pass dummies through this helper.
    private fun parse(vararg args: String): CliArgs =
        CliArgs.from(
            args = arrayOf(*args),
            geminiApiKey = DUMMY_GEMINI_KEY,
            openRouterApiKey = DUMMY_OPENROUTER_KEY,
        )

    // --- mode selection: admin / chat / oneshot ----------------------

    @Test
    fun `dash sessions returns ListSessions object`() {
        val parsed = parse("-sessions")
        assertEquals(CliArgs.ListSessions, parsed)
    }

    @Test
    fun `dash sessions wins over prompt`() {
        // Documented behaviour: in list mode every other flag is ignored.
        val parsed = parse("-sessions", "-prompt", "ignored")
        assertEquals(CliArgs.ListSessions, parsed)
    }

    @Test
    fun `dash clean returns Clean object`() {
        val parsed = parse("-clean")
        assertEquals(CliArgs.Clean, parsed)
    }

    @Test
    fun `sessions and clean do not require any API key`() {
        // Read-only admin commands must work even without keys configured.
        // Smoke-test this separately from the dummy-key paths above.
        assertEquals(
            CliArgs.ListSessions,
            CliArgs.from(arrayOf("-sessions"), geminiApiKey = "", openRouterApiKey = ""),
        )
        assertEquals(
            CliArgs.Clean,
            CliArgs.from(arrayOf("-clean"), geminiApiKey = "", openRouterApiKey = ""),
        )
    }

    @Test
    fun `bare prompt becomes Chat with default Gemini provider and no knobs`() {
        val parsed = parse("-prompt", "hi")
        val chat = assertIs<CliArgs.Chat>(parsed)
        assertEquals("hi", chat.prompt)
        assertNull(chat.maxTokens)
        assertNull(chat.stopSequences)
        assertNull(chat.endSequence)
        assertNull(chat.temperature)
        assertNull(chat.session)
        val gemini = assertIs<ModelProvider.Gemini>(chat.modelProvider)
        assertEquals(GeminiModel.Default, gemini.model)
        assertEquals(DUMMY_GEMINI_KEY, gemini.apiKey)
    }

    @Test
    fun `Chat carries all knobs when present`() {
        val parsed = parse(
            "-prompt", "hi",
            "-session", "foo",
            "-maxTokens", "100",
            "-temperature", "0.7",
            "-stopSequence", "stop", "halt",
            "-endSequence", "[END]",
            "-model", "gemini-2.5-flash",
        )
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
    fun `multi-word prompt joined with spaces`() {
        val parsed = parse("-prompt", "tell", "me", "a", "joke")
        val chat = assertIs<CliArgs.Chat>(parsed)
        assertEquals("tell me a joke", chat.prompt)
    }

    @Test
    fun `dash oneshot returns OneShot variant`() {
        val parsed = parse("-prompt", "hi", "-oneshot")
        val oneShot = assertIs<CliArgs.OneShot>(parsed)
        assertEquals("hi", oneShot.prompt)
    }

    @Test
    fun `OneShot carries generation knobs`() {
        val parsed = parse(
            "-prompt", "hi",
            "-oneshot",
            "-maxTokens", "42",
            "-temperature", "0.5",
            "-model", "gemini-2.5-flash",
        )
        val oneShot = assertIs<CliArgs.OneShot>(parsed)
        assertEquals(42, oneShot.maxTokens)
        assertEquals(0.5, oneShot.temperature)
        val gemini = assertIs<ModelProvider.Gemini>(oneShot.modelProvider)
        assertEquals(GeminiModel.Known.Gemini25Flash, gemini.model)
    }

    // --- provider routing & model parsing ----------------------------

    @Test
    fun `explicit -provider gemini matches the default`() {
        val parsed = parse("-prompt", "hi", "-provider", "gemini")
        val chat = assertIs<CliArgs.Chat>(parsed)
        val gemini = assertIs<ModelProvider.Gemini>(chat.modelProvider)
        assertEquals(GeminiModel.Default, gemini.model)
    }

    @Test
    fun `-provider openrouter without -model uses OpenRouter default`() {
        val parsed = parse("-prompt", "hi", "-provider", "openrouter")
        val chat = assertIs<CliArgs.Chat>(parsed)
        val or = assertIs<ModelProvider.OpenRouter>(chat.modelProvider)
        assertEquals(OpenRouterModel.Default, or.model)
        assertEquals(DUMMY_OPENROUTER_KEY, or.apiKey)
    }

    @Test
    fun `-provider openrouter with known -model resolves to Known entry`() {
        val parsed = parse(
            "-prompt", "hi",
            "-provider", "openrouter",
            "-model", "openrouter/auto:free",
        )
        val or = assertIs<ModelProvider.OpenRouter>((parsed as CliArgs.PromptCommand).modelProvider)
        assertEquals(OpenRouterModel.Known.AutoFree, or.model)
    }

    @Test
    fun `-provider openrouter with unknown -model falls through to Custom`() {
        val parsed = parse(
            "-prompt", "hi",
            "-provider", "openrouter",
            "-model", "some/custom:id",
        )
        val or = assertIs<ModelProvider.OpenRouter>((parsed as CliArgs.PromptCommand).modelProvider)
        assertEquals(OpenRouterModel.Custom("some/custom:id"), or.model)
    }

    @Test
    fun `-provider gemini with unknown -model falls through to Custom`() {
        val parsed = parse("-prompt", "hi", "-model", "totally-made-up")
        val gemini = assertIs<ModelProvider.Gemini>((parsed as CliArgs.PromptCommand).modelProvider)
        assertEquals(GeminiModel.Custom("totally-made-up"), gemini.model)
    }

    @Test
    fun `unknown -provider value is rejected`() {
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parse("-prompt", "hi", "-provider", "anthropic")
        }
        assertEquals("-provider", ex.argName)
        assertEquals("anthropic", ex.rawValue)
    }

    @Test
    fun `selecting gemini without GEMINI_API_KEY raises MissingRequiredArgument`() {
        val ex = assertFailsWith<CliArgsException.MissingRequiredArgument> {
            CliArgs.from(
                arrayOf("-prompt", "hi"),
                geminiApiKey = "",
                openRouterApiKey = DUMMY_OPENROUTER_KEY,
            )
        }
        assertEquals("GEMINI_API_KEY", ex.argName)
    }

    @Test
    fun `selecting openrouter without OPENROUTER_API_KEY raises MissingRequiredArgument`() {
        val ex = assertFailsWith<CliArgsException.MissingRequiredArgument> {
            CliArgs.from(
                arrayOf("-prompt", "hi", "-provider", "openrouter"),
                geminiApiKey = DUMMY_GEMINI_KEY,
                openRouterApiKey = "",
            )
        }
        assertEquals("OPENROUTER_API_KEY", ex.argName)
    }

    // --- mode conflicts and validation errors ------------------------

    @Test
    fun `oneshot rejects session`() {
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parse("-prompt", "hi", "-oneshot", "-session", "foo")
        }
        assertEquals("-session", ex.argName)
    }

    @Test
    fun `oneshot without prompt is missing required argument`() {
        val ex = assertFailsWith<CliArgsException.MissingRequiredArgument> {
            parse("-oneshot")
        }
        assertEquals("-prompt", ex.argName)
    }

    @Test
    fun `sessions and clean together is rejected`() {
        assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parse("-sessions", "-clean")
        }
    }

    @Test
    fun `sessions and oneshot together is rejected`() {
        assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parse("-sessions", "-oneshot", "-prompt", "x")
        }
    }

    @Test
    fun `clean and oneshot together is rejected`() {
        assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parse("-clean", "-oneshot", "-prompt", "x")
        }
    }

    @Test
    fun `non-integer maxTokens rejected`() {
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parse("-prompt", "hi", "-maxTokens", "abc")
        }
        assertEquals("-maxTokens", ex.argName)
    }

    @Test
    fun `non-decimal temperature rejected`() {
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parse("-prompt", "hi", "-temperature", "hot")
        }
        assertEquals("-temperature", ex.argName)
    }

    @Test
    fun `too many stop sequences rejected`() {
        val ex = assertFailsWith<CliArgsException.TooManyValues> {
            parse("-prompt", "hi", "-stopSequence", "a", "b", "c", "d", "e", "f")
        }
        assertEquals("-stopSequence", ex.argName)
        assertEquals(6, ex.count)
        assertEquals(CliArgs.MAX_STOP_SEQUENCES, ex.maxAllowed)
    }

    @Test
    fun `session name with whitespace is rejected`() {
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parse("-prompt", "hi", "-session", "bad", "name", "with", "spaces")
        }
        assertEquals("-session", ex.argName)
    }

    @Test
    fun `session name longer than 64 chars is rejected`() {
        val longName = "a".repeat(65)
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parse("-prompt", "hi", "-session", longName)
        }
        assertEquals("-session", ex.argName)
    }

    @Test
    fun `prompt missing in default mode is missing required argument`() {
        val ex = assertFailsWith<CliArgsException.MissingRequiredArgument> {
            parse()
        }
        assertEquals("-prompt", ex.argName)
    }

    // --- USAGE smoke check ------------------------------------------

    @Test
    fun `USAGE mentions every public flag`() {
        // Light coverage so a future flag-renaming doesn't silently
        // leave USAGE stale.
        val usage = CliArgs.USAGE
        listOf(
            "-prompt", "-provider", "-maxTokens", "-stopSequence", "-endSequence",
            "-temperature", "-model", "-session", "-oneshot",
            "-sessions", "-clean",
        ).forEach { flag ->
            assertTrue(usage.contains(flag), "USAGE missing mention of $flag")
        }
    }

    private companion object {
        const val DUMMY_GEMINI_KEY = "test-gemini-key"
        const val DUMMY_OPENROUTER_KEY = "test-openrouter-key"
    }
}
