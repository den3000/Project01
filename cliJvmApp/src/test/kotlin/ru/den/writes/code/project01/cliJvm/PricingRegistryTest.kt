package ru.den.writes.code.project01.cliJvm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PricingRegistryTest {

    @Test
    fun `lookup returns null for unknown id`() {
        assertNull(PricingRegistry.lookup("totally-made-up-model-id"))
    }

    @Test
    fun `lookup returns rates for a known Gemini model`() {
        val p = assertNotNull(PricingRegistry.lookup("gemini-2.5-flash-lite"))
        assertEquals(0.10, p.inputUsdPer1M)
        assertEquals(0.40, p.outputUsdPer1M)
        assertEquals(1_000_000, p.contextWindowTokens)
    }

    @Test
    fun `lookup returns zero rates for free OpenRouter models`() {
        val p = assertNotNull(PricingRegistry.lookup("google/gemma-3-27b-it"))
        assertEquals(0.0, p.inputUsdPer1M)
        assertEquals(0.0, p.outputUsdPer1M)
    }

    @Test
    fun `cost formula includes thoughts under the output rate`() {
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
        assertEquals(0.00038, PricingRegistry.cost(usage, pricing), 1e-12)
    }

    @Test
    fun `cost with zero usage is zero`() {
        val pricing = ModelPricing(inputUsdPer1M = 1.50, outputUsdPer1M = 9.00)
        val usage = Usage(promptTokens = 0, outputTokens = 0, thoughtsTokens = 0, totalTokens = 0)
        assertEquals(0.0, PricingRegistry.cost(usage, pricing))
    }
}
