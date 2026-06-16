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
        assertNull(chat.feedFile)
        assertEquals(2500, chat.chunkChars)
        assertEquals("", chat.feedInstruction)
        assertEquals(false, chat.byLine)
        assertEquals(ContextStrategyKind.FULL, chat.strategy)
        assertEquals(6, chat.keepLast)
        assertEquals(10, chat.summarizeEvery)
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
            "-model", "meta-llama/llama-3.3-70b-instruct:free",
        )
        val or = assertIs<ModelProvider.OpenRouter>((parsed as CliArgs.PromptCommand).modelProvider)
        assertEquals(OpenRouterModel.Known.Llama33_70bFree, or.model)
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

    // --- feed mode ---------------------------------------------------

    @Test
    fun `feedFile sets feed config on Chat with chunkChars default`() {
        val parsed = parse("-prompt", "hi", "-feedFile", "/tmp/data.txt")
        val chat = assertIs<CliArgs.Chat>(parsed)
        assertEquals("/tmp/data.txt", chat.feedFile)
        assertEquals(2500, chat.chunkChars)
        assertEquals("", chat.feedInstruction)
    }

    @Test
    fun `feedFile with chunkChars and instruction carries them through`() {
        val parsed = parse(
            "-prompt", "hi",
            "-feedFile", "/tmp/data.txt",
            "-chunkChars", "777",
            "-feedInstruction", "Briefly summarise:",
        )
        val chat = assertIs<CliArgs.Chat>(parsed)
        assertEquals(777, chat.chunkChars)
        assertEquals("Briefly summarise:", chat.feedInstruction)
    }

    @Test
    fun `feedFile is rejected alongside oneshot`() {
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parse("-prompt", "hi", "-oneshot", "-feedFile", "/tmp/data.txt")
        }
        assertEquals("-feedFile", ex.argName)
    }

    @Test
    fun `chunkChars without feedFile is rejected`() {
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parse("-prompt", "hi", "-chunkChars", "1000")
        }
        assertEquals("-chunkChars", ex.argName)
    }

    @Test
    fun `feedInstruction without feedFile is rejected`() {
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parse("-prompt", "hi", "-feedInstruction", "go go")
        }
        assertEquals("-feedInstruction", ex.argName)
    }

    @Test
    fun `non-positive chunkChars is rejected`() {
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parse("-prompt", "hi", "-feedFile", "/tmp/x.txt", "-chunkChars", "0")
        }
        assertEquals("-chunkChars", ex.argName)
    }

    // --- byLine feed mode (line-by-line) ----------------------------

    @Test
    fun `byLine sets line-feed mode on Chat`() {
        val chat = assertIs<CliArgs.Chat>(parse("-prompt", "hi", "-feedFile", "/tmp/x.txt", "-byLine"))
        assertTrue(chat.byLine)
        assertEquals("/tmp/x.txt", chat.feedFile)
    }

    @Test
    fun `byLine works with feedInstruction`() {
        val chat = assertIs<CliArgs.Chat>(
            parse("-prompt", "hi", "-feedFile", "/tmp/x.txt", "-byLine", "-feedInstruction", "Comment:")
        )
        assertTrue(chat.byLine)
        assertEquals("Comment:", chat.feedInstruction)
    }

    @Test
    fun `byLine without feedFile is rejected`() {
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parse("-prompt", "hi", "-byLine")
        }
        assertEquals("-byLine", ex.argName)
    }

    @Test
    fun `byLine together with chunkChars is rejected`() {
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parse("-prompt", "hi", "-feedFile", "/tmp/x.txt", "-byLine", "-chunkChars", "100")
        }
        assertEquals("-chunkChars", ex.argName)
    }

    @Test
    fun `byLine is rejected alongside oneshot`() {
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parse("-prompt", "hi", "-oneshot", "-byLine")
        }
        assertEquals("-byLine", ex.argName)
    }

    // --- inflate ----------------------------------------------------

    @Test
    fun `inflate parses session and N`() {
        val parsed = parse("-inflate", "10", "-session", "foo")
        val inflate = assertIs<CliArgs.Inflate>(parsed)
        assertEquals("foo", inflate.sessionId)
        assertEquals(10, inflate.n)
    }

    @Test
    fun `inflate requires session`() {
        val ex = assertFailsWith<CliArgsException.MissingRequiredArgument> {
            parse("-inflate", "5")
        }
        assertEquals("-session", ex.argName)
    }

    @Test
    fun `inflate rejects non-positive N`() {
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parse("-inflate", "0", "-session", "foo")
        }
        assertEquals("-inflate", ex.argName)
    }

    @Test
    fun `inflate rejects mixing with prompt`() {
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parse("-inflate", "5", "-session", "foo", "-prompt", "hi")
        }
        assertEquals("-prompt", ex.argName)
    }

    @Test
    fun `inflate rejects mixing with feedFile`() {
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parse("-inflate", "5", "-session", "foo", "-feedFile", "/tmp/x")
        }
        assertEquals("-feedFile", ex.argName)
    }

    @Test
    fun `inflate works without API keys`() {
        // Pure DB op — no LLM call — so it must not require either key.
        val parsed = CliArgs.from(
            arrayOf("-inflate", "3", "-session", "foo"),
            geminiApiKey = "",
            openRouterApiKey = "",
        )
        val inflate = assertIs<CliArgs.Inflate>(parsed)
        assertEquals(3, inflate.n)
        assertEquals("foo", inflate.sessionId)
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
            "-sessions", "-clean", "-inflate",
            "-feedFile", "-chunkChars", "-feedInstruction", "-byLine",
            "-compress", "-keepLast", "-summarizeEvery", "-strategy",
        ).forEach { flag ->
            assertTrue(usage.contains(flag), "USAGE missing mention of $flag")
        }
    }

    // --- compression (Day-9) ----------------------------------------

    @Test
    fun `compress flag enables compression with default knobs`() {
        val chat = assertIs<CliArgs.Chat>(parse("-prompt", "hi", "-compress"))
        assertEquals(ContextStrategyKind.SUMMARY, chat.strategy)
        assertEquals(6, chat.keepLast)
        assertEquals(10, chat.summarizeEvery)
    }

    @Test
    fun `compress carries keepLast and summarizeEvery when set`() {
        val chat = assertIs<CliArgs.Chat>(
            parse("-prompt", "hi", "-compress", "-keepLast", "4", "-summarizeEvery", "20")
        )
        assertEquals(ContextStrategyKind.SUMMARY, chat.strategy)
        assertEquals(4, chat.keepLast)
        assertEquals(20, chat.summarizeEvery)
    }

    @Test
    fun `keepLast zero is accepted`() {
        val chat = assertIs<CliArgs.Chat>(parse("-prompt", "hi", "-compress", "-keepLast", "0"))
        assertEquals(0, chat.keepLast)
    }

    @Test
    fun `keepLast without compress is rejected`() {
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parse("-prompt", "hi", "-keepLast", "4")
        }
        assertEquals("-keepLast", ex.argName)
    }

    @Test
    fun `summarizeEvery without compress is rejected`() {
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parse("-prompt", "hi", "-summarizeEvery", "10")
        }
        assertEquals("-summarizeEvery", ex.argName)
    }

    @Test
    fun `negative keepLast is rejected`() {
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parse("-prompt", "hi", "-compress", "-keepLast", "-3")
        }
        assertEquals("-keepLast", ex.argName)
    }

    @Test
    fun `summarizeEvery below 2 is rejected`() {
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parse("-prompt", "hi", "-compress", "-summarizeEvery", "1")
        }
        assertEquals("-summarizeEvery", ex.argName)
    }

    @Test
    fun `compress is rejected alongside oneshot`() {
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parse("-prompt", "hi", "-oneshot", "-compress")
        }
        assertEquals("-compress", ex.argName)
    }

    @Test
    fun `keepLast is rejected alongside oneshot`() {
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parse("-prompt", "hi", "-oneshot", "-keepLast", "4")
        }
        assertEquals("-keepLast", ex.argName)
    }

    @Test
    fun `compress is rejected alongside inflate`() {
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parse("-inflate", "5", "-session", "foo", "-compress")
        }
        assertEquals("-compress", ex.argName)
    }

    // --- strategy switch (Day-10) -----------------------------------

    @Test
    fun `strategy window selects WINDOW`() {
        val chat = assertIs<CliArgs.Chat>(parse("-prompt", "hi", "-strategy", "window"))
        assertEquals(ContextStrategyKind.WINDOW, chat.strategy)
    }

    @Test
    fun `strategy summary selects SUMMARY`() {
        val chat = assertIs<CliArgs.Chat>(parse("-prompt", "hi", "-strategy", "summary"))
        assertEquals(ContextStrategyKind.SUMMARY, chat.strategy)
    }

    @Test
    fun `strategy facts selects FACTS`() {
        val chat = assertIs<CliArgs.Chat>(parse("-prompt", "hi", "-strategy", "facts"))
        assertEquals(ContextStrategyKind.FACTS, chat.strategy)
    }

    @Test
    fun `keepLast is accepted under strategy facts`() {
        val chat = assertIs<CliArgs.Chat>(parse("-prompt", "hi", "-strategy", "facts", "-keepLast", "4"))
        assertEquals(ContextStrategyKind.FACTS, chat.strategy)
        assertEquals(4, chat.keepLast)
    }

    @Test
    fun `summarizeEvery is rejected under strategy facts`() {
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parse("-prompt", "hi", "-strategy", "facts", "-summarizeEvery", "5")
        }
        assertEquals("-summarizeEvery", ex.argName)
    }

    @Test
    fun `strategy full is both the default and an explicit value`() {
        assertEquals(ContextStrategyKind.FULL, assertIs<CliArgs.Chat>(parse("-prompt", "hi")).strategy)
        assertEquals(
            ContextStrategyKind.FULL,
            assertIs<CliArgs.Chat>(parse("-prompt", "hi", "-strategy", "full")).strategy,
        )
    }

    @Test
    fun `unknown strategy value is rejected`() {
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parse("-prompt", "hi", "-strategy", "bogus")
        }
        assertEquals("-strategy", ex.argName)
    }

    @Test
    fun `compress is shorthand for strategy summary`() {
        assertEquals(
            ContextStrategyKind.SUMMARY,
            assertIs<CliArgs.Chat>(parse("-prompt", "hi", "-compress")).strategy,
        )
        // -compress paired with an explicit -strategy summary is allowed.
        assertEquals(
            ContextStrategyKind.SUMMARY,
            assertIs<CliArgs.Chat>(parse("-prompt", "hi", "-compress", "-strategy", "summary")).strategy,
        )
    }

    @Test
    fun `compress paired with a conflicting strategy is rejected`() {
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parse("-prompt", "hi", "-compress", "-strategy", "window")
        }
        assertEquals("-compress", ex.argName)
    }

    @Test
    fun `keepLast is accepted under strategy window`() {
        val chat = assertIs<CliArgs.Chat>(parse("-prompt", "hi", "-strategy", "window", "-keepLast", "4"))
        assertEquals(ContextStrategyKind.WINDOW, chat.strategy)
        assertEquals(4, chat.keepLast)
    }

    @Test
    fun `summarizeEvery is rejected under strategy window`() {
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parse("-prompt", "hi", "-strategy", "window", "-summarizeEvery", "5")
        }
        assertEquals("-summarizeEvery", ex.argName)
    }

    @Test
    fun `strategy is rejected alongside oneshot`() {
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parse("-prompt", "hi", "-oneshot", "-strategy", "window")
        }
        assertEquals("-strategy", ex.argName)
    }

    private companion object {
        const val DUMMY_GEMINI_KEY = "test-gemini-key"
        const val DUMMY_OPENROUTER_KEY = "test-openrouter-key"
    }
}
