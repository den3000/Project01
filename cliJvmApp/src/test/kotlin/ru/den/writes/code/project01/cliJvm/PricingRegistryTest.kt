package ru.den.writes.code.project01.cliJvm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PricingRegistryTest {

    //region lookup

    @Test
    fun `when lookup called with unknown id - then null returned`() {
        // given
        val unknownId = "totally-made-up-model-id"

        // when
        val actual = PricingRegistry.lookup(unknownId)

        // then
        assertNull(actual)
    }

    @Test
    fun `when lookup called with known Gemini model - then rates and context window returned`() {
        // given
        val modelId = "gemini-2.5-flash-lite"

        // when
        val actual = assertNotNull(PricingRegistry.lookup(modelId))

        // then
        assertEquals(0.10, actual.inputUsdPer1M)
        assertEquals(0.40, actual.outputUsdPer1M)
        assertEquals(1_000_000, actual.contextWindowTokens)
    }

    @Test
    fun `when lookup called with free OpenRouter model - then zero rates returned`() {
        // given
        val modelId = "meta-llama/llama-3.3-70b-instruct:free"

        // when
        val actual = assertNotNull(PricingRegistry.lookup(modelId))

        // then
        assertEquals(0.0, actual.inputUsdPer1M)
        assertEquals(0.0, actual.outputUsdPer1M)
        assertEquals(131_072, actual.contextWindowTokens)
    }

    @Test
    fun `when lookup called with paid OpenRouter model - then paid rates returned`() {
        // given
        // google/gemma-3-27b-it has no :free variant anymore — kept in the
        // registry as a paid model so -model runs still report real cost.
        val modelId = "google/gemma-3-27b-it"

        // when
        val actual = assertNotNull(PricingRegistry.lookup(modelId))

        // then
        assertEquals(0.08, actual.inputUsdPer1M)
        assertEquals(0.16, actual.outputUsdPer1M)
        assertEquals(131_072, actual.contextWindowTokens)
    }

    @Test
    fun `when lookup called with Hugging Face routed model - then approximate rates returned`() {
        // given
        // HF Router prices are per-provider; the registry holds an
        // approximation so the footer still shows a real cost figure
        // instead of "(no pricing)" for the typed catalog ids.
        val modelId = "meta-llama/Llama-3.3-70B-Instruct"

        // when
        val actual = assertNotNull(PricingRegistry.lookup(modelId))

        // then
        assertEquals(0.23, actual.inputUsdPer1M)
        assertEquals(0.40, actual.outputUsdPer1M)
        assertEquals(131_072, actual.contextWindowTokens)
    }
    //endregion

    //region cost

    @Test
    fun `when cost called with non-zero usage - then thoughts billed under output rate`() {
        // given
        // 1000 prompt × $0.10/M = 0.0001
        // (500 output + 200 thoughts) × $0.40/M = 0.00028
        // total = 0.00038
        val pricing = ModelPricing(inputUsdPer1M = 0.10, outputUsdPer1M = 0.40)
        val usage = Usage(
            promptTokens = 1000,
            outputTokens = 500,
            thoughtsTokens = 200,
            totalTokens = 1700,
        )

        // when
        val actual = PricingRegistry.cost(usage, pricing)

        // then
        val expected = 0.00038
        assertEquals(expected, actual, 1e-12)
    }

    @Test
    fun `when cost called with zero usage - then zero returned`() {
        // given
        val pricing = ModelPricing(inputUsdPer1M = 1.50, outputUsdPer1M = 9.00)
        val usage = Usage(promptTokens = 0, outputTokens = 0, thoughtsTokens = 0, totalTokens = 0)

        // when
        val actual = PricingRegistry.cost(usage, pricing)

        // then
        val expected = 0.0
        assertEquals(expected, actual)
    }
    //endregion
}
