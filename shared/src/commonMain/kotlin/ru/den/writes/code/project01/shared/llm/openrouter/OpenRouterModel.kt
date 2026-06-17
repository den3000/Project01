package ru.den.writes.code.project01.shared.llm.openrouter

/**
 * Typed OpenRouter model identifier.
 *
 * Mirrors [ru.den.writes.code.project01.shared.llm.gemini.GeminiModel] in shape: a small [Known] catalog plus a
 * [Custom] escape hatch. The catalog leans on free-tier (`:free`)
 * models — cheap experimentation is the whole point of going through
 * OpenRouter. The default is [Known.Auto], the meta-router that picks
 * a model at request time. Note it is NOT a `:free` id, so it may route
 * to a paid model (priced per-request — see [PricingRegistry]).
 *
 * OpenRouter rotates its free roster fast: every `:free` id below was
 * verified live against https://openrouter.ai/api/v1/models in June
 * 2026, but ids come and go — update by hand when one starts 404-ing.
 * In the meantime pass any current id via `-model`; unknown ids fall
 * through to [Custom] and are forwarded verbatim.
 */
sealed interface OpenRouterModel {
    val id: String

    /**
     * The meta-router plus a handful of free-tier models across familiar
     * families. Verified live June 2026; expect the `:free` ids to drift.
     */
    enum class Known(override val id: String) : OpenRouterModel {
        /** Meta-router — picks a model at request time (may be paid). */
        Auto("openrouter/auto"),
        Llama33_70bFree("meta-llama/llama-3.3-70b-instruct:free"),
        Gemma4_31bFree("google/gemma-4-31b-it:free"),
        Qwen3CoderFree("qwen/qwen3-coder:free"),
        Nemotron3Super120bFree("nvidia/nemotron-3-super-120b-a12b:free"),
    }

    /** Escape hatch for ids not yet in [Known]. */
    data class Custom(override val id: String) : OpenRouterModel

    companion object {
        val Default: OpenRouterModel = Known.Auto

        /** Resolves a raw id to a [Known] entry, falling back to [Custom]. */
        fun fromId(id: String): OpenRouterModel =
            Known.entries.firstOrNull { it.id == id } ?: Custom(id)
    }
}