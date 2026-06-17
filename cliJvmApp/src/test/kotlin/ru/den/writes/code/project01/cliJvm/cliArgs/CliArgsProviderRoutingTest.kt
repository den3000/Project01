package ru.den.writes.code.project01.cliJvm.cliArgs

import ru.den.writes.code.project01.shared.llm.gemini.GeminiModel
import ru.den.writes.code.project01.shared.llm.huggingface.HuggingFaceModel
import ru.den.writes.code.project01.shared.llm.ModelProvider
import ru.den.writes.code.project01.shared.llm.openrouter.OpenRouterModel
import ru.den.writes.code.project01.cliJvm.CliArgs
import ru.den.writes.code.project01.cliJvm.CliArgsException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class CliArgsProviderRoutingTest {

    //region gemini

    @Test
    fun `when -provider gemini passed - then matches the default provider`() {
        // given
        val args = arrayOf("-prompt", "hi", "-provider", "gemini")

        // when
        val parsed = parseCliArgsWithDummyKeys(*args)

        // then
        val chat = assertIs<CliArgs.Chat>(parsed)
        val gemini = assertIs<ModelProvider.Gemini>(chat.modelProvider)
        assertEquals(GeminiModel.Default, gemini.model)
    }

    @Test
    fun `when -provider gemini with unknown -model - then falls through to Custom`() {
        // given
        val args = arrayOf("-prompt", "hi", "-model", "totally-made-up")

        // when
        val parsed = parseCliArgsWithDummyKeys(*args)

        // then
        val gemini = assertIs<ModelProvider.Gemini>((parsed as CliArgs.PromptCommand).modelProvider)
        val expected = GeminiModel.Custom("totally-made-up")
        assertEquals(expected, gemini.model)
    }

    @Test
    fun `when gemini selected without GEMINI_API_KEY - then MissingRequiredArgument thrown`() {
        // given
        val args = arrayOf("-prompt", "hi")

        // when
        val ex = assertFailsWith<CliArgsException.MissingRequiredArgument> {
            CliArgs.from(args, geminiApiKey = "", openRouterApiKey = DUMMY_OPENROUTER_KEY)
        }

        // then
        assertEquals("GEMINI_API_KEY", ex.argName)
    }
    //endregion

    //region openrouter

    @Test
    fun `when -provider openrouter without -model - then OpenRouter default used`() {
        // given
        val args = arrayOf("-prompt", "hi", "-provider", "openrouter")

        // when
        val parsed = parseCliArgsWithDummyKeys(*args)

        // then
        val chat = assertIs<CliArgs.Chat>(parsed)
        val or = assertIs<ModelProvider.OpenRouter>(chat.modelProvider)
        assertEquals(OpenRouterModel.Default, or.model)
        assertEquals(DUMMY_OPENROUTER_KEY, or.apiKey)
    }

    @Test
    fun `when -provider openrouter with known -model - then resolves to Known entry`() {
        // given
        val args = arrayOf(
            "-prompt", "hi",
            "-provider", "openrouter",
            "-model", "meta-llama/llama-3.3-70b-instruct:free",
        )

        // when
        val parsed = parseCliArgsWithDummyKeys(*args)

        // then
        val or = assertIs<ModelProvider.OpenRouter>((parsed as CliArgs.PromptCommand).modelProvider)
        assertEquals(OpenRouterModel.Known.Llama33_70bFree, or.model)
    }

    @Test
    fun `when -provider openrouter with unknown -model - then falls through to Custom`() {
        // given
        val args = arrayOf(
            "-prompt", "hi",
            "-provider", "openrouter",
            "-model", "some/custom:id",
        )

        // when
        val parsed = parseCliArgsWithDummyKeys(*args)

        // then
        val or = assertIs<ModelProvider.OpenRouter>((parsed as CliArgs.PromptCommand).modelProvider)
        val expected = OpenRouterModel.Custom("some/custom:id")
        assertEquals(expected, or.model)
    }

    @Test
    fun `when openrouter selected without OPENROUTER_API_KEY - then MissingRequiredArgument thrown`() {
        // given
        val args = arrayOf("-prompt", "hi", "-provider", "openrouter")

        // when
        val ex = assertFailsWith<CliArgsException.MissingRequiredArgument> {
            CliArgs.from(args, geminiApiKey = DUMMY_GEMINI_KEY, openRouterApiKey = "")
        }

        // then
        assertEquals("OPENROUTER_API_KEY", ex.argName)
    }
    //endregion

    //region huggingface

    @Test
    fun `when -provider huggingface without -model - then HuggingFace default used`() {
        // given
        val args = arrayOf("-prompt", "hi", "-provider", "huggingface")

        // when
        val parsed = parseCliArgsWithDummyKeys(*args)

        // then
        val chat = assertIs<CliArgs.Chat>(parsed)
        val hf = assertIs<ModelProvider.HuggingFace>(chat.modelProvider)
        assertEquals(HuggingFaceModel.Default, hf.model)
        assertEquals(DUMMY_HUGGINGFACE_KEY, hf.apiKey)
    }

    @Test
    fun `when -provider huggingface with known -model - then resolves to Known entry`() {
        // given
        val args = arrayOf(
            "-prompt", "hi",
            "-provider", "huggingface",
            "-model", "deepseek-ai/DeepSeek-R1",
        )

        // when
        val parsed = parseCliArgsWithDummyKeys(*args)

        // then
        val hf = assertIs<ModelProvider.HuggingFace>((parsed as CliArgs.PromptCommand).modelProvider)
        assertEquals(HuggingFaceModel.Known.DeepSeekR1, hf.model)
    }

    @Test
    fun `when -provider huggingface with unknown -model - then falls through to Custom`() {
        // given
        val args = arrayOf(
            "-prompt", "hi",
            "-provider", "huggingface",
            "-model", "some-org/not-yet-known-7b",
        )

        // when
        val parsed = parseCliArgsWithDummyKeys(*args)

        // then
        val hf = assertIs<ModelProvider.HuggingFace>((parsed as CliArgs.PromptCommand).modelProvider)
        val expected = HuggingFaceModel.Custom("some-org/not-yet-known-7b")
        assertEquals(expected, hf.model)
    }

    @Test
    fun `when huggingface selected without HUGGINGFACE_API_KEY - then MissingRequiredArgument thrown`() {
        // given
        val args = arrayOf("-prompt", "hi", "-provider", "huggingface")

        // when
        val ex = assertFailsWith<CliArgsException.MissingRequiredArgument> {
            CliArgs.from(
                args,
                geminiApiKey = DUMMY_GEMINI_KEY,
                openRouterApiKey = DUMMY_OPENROUTER_KEY,
                huggingFaceApiKey = "",
            )
        }

        // then
        assertEquals("HUGGINGFACE_API_KEY", ex.argName)
    }
    //endregion

    //region unknown provider

    @Test
    fun `when -provider has unknown value - then InvalidArgumentValue thrown`() {
        // given
        val args = arrayOf("-prompt", "hi", "-provider", "anthropic")

        // when
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parseCliArgsWithDummyKeys(*args)
        }

        // then
        assertEquals("-provider", ex.argName)
        assertEquals("anthropic", ex.rawValue)
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
