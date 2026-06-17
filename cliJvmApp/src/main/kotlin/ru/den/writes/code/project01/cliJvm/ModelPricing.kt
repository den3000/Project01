package ru.den.writes.code.project01.cliJvm

import ru.den.writes.code.project01.shared.llm.Usage

/**
 * Per-model pricing and context-window data used to derive cost from
 * a token count.
 *
 * Rates are in USD per 1,000,000 tokens — that's how providers quote them.
 * [contextWindowTokens] is the input-side limit (prompt + history). null
 * means "unknown / not modelled here".
 *
 * Thinking-capable Gemini models bill their reasoning tokens at the
 * output rate, hence the cost formula in [PricingRegistry.cost] adds
 * `thoughtsTokens` to `outputTokens` before applying the output rate.
 */
internal data class ModelPricing(
    val inputUsdPer1M: Double,
    val outputUsdPer1M: Double,
    val contextWindowTokens: Int? = null,
)

/**
 * Static, hand-maintained lookup of [ModelPricing] by provider-specific
 * model id (the same string that travels on the wire). Updated by hand
 * when a new model shows up or rates change.
 *
 * Models not in the map produce `null` on [lookup]; the caller
 * (typically [SessionStats.seedFrom] or the per-turn footer printer)
 * treats that as "we don't know the cost" and prints a `$? (no pricing)`
 * marker instead of guessing.
 *
 * Rates current as of June 2026. Sources:
 * - Gemini: https://ai.google.dev/gemini-api/docs/pricing
 * - OpenRouter free roster (all 0/0): https://openrouter.ai/models?max_price=0
 * - Hugging Face Router: https://huggingface.co/docs/inference-providers/en/pricing
 */
internal object PricingRegistry {
    private val byId: Map<String, ModelPricing> = mapOf(
        // ---- Gemini text models (paid tier) -----------------------------
        // 2.5 Pro: tiered pricing (≤200K vs >200K). We use the lower tier
        // here as an approximation — most chat sessions stay under 200K.
        "gemini-2.5-pro" to ModelPricing(
            inputUsdPer1M = 1.25,
            outputUsdPer1M = 10.00,
            contextWindowTokens = 2_000_000,
        ),
        "gemini-2.5-flash" to ModelPricing(
            inputUsdPer1M = 0.30,
            outputUsdPer1M = 2.50,
            contextWindowTokens = 1_000_000,
        ),
        "gemini-2.5-flash-lite" to ModelPricing(
            inputUsdPer1M = 0.10,
            outputUsdPer1M = 0.40,
            contextWindowTokens = 1_000_000,
        ),

        // 3.x preview/GA. Numbers below are from the project's prior
        // experiments; adjust against Google's pricing page if they
        // change.
        "gemini-3.1-pro-preview" to ModelPricing(
            inputUsdPer1M = 1.25,
            outputUsdPer1M = 10.00,
            contextWindowTokens = 1_000_000,
        ),
        // The id literally omits the `.1` — that's how Google ships it.
        "gemini-3-flash-preview" to ModelPricing(
            inputUsdPer1M = 0.50,
            outputUsdPer1M = 3.00,
            contextWindowTokens = 1_000_000,
        ),
        "gemini-3.1-flash-lite" to ModelPricing(
            inputUsdPer1M = 0.10,
            outputUsdPer1M = 0.40,
            contextWindowTokens = 1_000_000,
        ),
        "gemini-3.5-flash" to ModelPricing(
            inputUsdPer1M = 1.50,
            outputUsdPer1M = 9.00,
            contextWindowTokens = 1_000_000,
        ),

        // ---- OpenRouter -------------------------------------------------
        // Rates + context windows verified live against
        // https://openrouter.ai/api/v1/models in June 2026. The `:free`
        // roster rotates fast, so this list will drift — refresh by hand.
        //
        // The `openrouter/auto` meta-router is deliberately absent: it
        // prices per-request depending on which model it routes to, so
        // there's no fixed rate to record. Out-of-registry → the footer
        // shows `cost=$? (no pricing)` and skips the context line, which
        // is the honest answer for a router.
        //
        // Free-tier models bill $0/$0. `google/gemma-3-27b-it` is the one
        // PAID entry here (its `:free` variant no longer exists) — kept so
        // a `-model google/gemma-3-27b-it` run still reports a real cost.
        "meta-llama/llama-3.3-70b-instruct:free" to ModelPricing(0.0, 0.0, 131_072),
        "google/gemma-4-31b-it:free"  to ModelPricing(0.0, 0.0, 262_144),
        "qwen/qwen3-coder:free"       to ModelPricing(0.0, 0.0, 1_048_576),
        "nvidia/nemotron-3-super-120b-a12b:free" to ModelPricing(0.0, 0.0, 1_000_000),
        "google/gemma-3-27b-it"       to ModelPricing(0.08, 0.16, 131_072),

        // ---- Hugging Face Router ---------------------------------------
        // The HF Router fans out to backing providers (Cerebras, Together,
        // Fireworks, DeepInfra…); the exact rate billed depends on which
        // provider answers the call, so these are *approximations* of the
        // commonly-routed price tier on June 2026. The CLI surfaces this
        // cost in the footer — treat it as a guideline, not a guarantee.
        // The HF $0.10/month free credit pool applies on top of these.
        "meta-llama/Llama-3.3-70B-Instruct"    to ModelPricing(0.23, 0.40, 131_072),
        "deepseek-ai/DeepSeek-R1"              to ModelPricing(3.00, 7.00, 65_536),
        "Qwen/Qwen3-4B-Thinking-2507"          to ModelPricing(0.05, 0.10, 262_144),
        "Qwen/Qwen3.6-35B-A3B"                 to ModelPricing(0.30, 0.60, 131_072),
        "openai/gpt-oss-120b"                  to ModelPricing(0.50, 1.50, 131_072),
    )

    fun lookup(modelId: String): ModelPricing? = byId[modelId]

    /**
     * Apply [pricing] to a [usage] count and return the cost in USD.
     *
     * Thinking-token billing: Gemini bills `thoughtsTokens` at the output
     * rate (they're generated by the model, just not surfaced in the
     * response text). OpenRouter has no separate field, so for them
     * `thoughtsTokens` is always 0 and this collapses to the obvious
     * input + output split.
     */
    fun cost(usage: Usage, pricing: ModelPricing): Double =
        (usage.promptTokens * pricing.inputUsdPer1M
            + (usage.outputTokens + usage.thoughtsTokens) * pricing.outputUsdPer1M
        ) / 1_000_000.0
}
