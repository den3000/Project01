package ru.den.writes.code.project01.cliJvm

/**
 * Typed OpenRouter model identifier.
 *
 * Mirrors [GeminiModel] in shape: a small [Known] catalog plus a
 * [Custom] escape hatch. The catalog leans on free-tier models —
 * that's the whole point of going through OpenRouter for cheap
 * experimentation. OpenRouter rotates its free roster fairly
 * often, so the default sits on [Known.AutoFree], the meta-router
 * that picks an available free model at request time.
 */
internal sealed interface OpenRouterModel {
    val id: String

    /**
     * A handful of free-tier OpenRouter models. The free roster
     * changes — update by hand when something stops working.
     */
    enum class Known(override val id: String) : OpenRouterModel {
        /** Meta-router that picks a free model at request time. */
        AutoFree("openrouter/auto:free"),
        DeepseekR1Free("deepseek/deepseek-r1:free"),
        Llama4MaverickFree("meta-llama/llama-4-maverick:free"),
        Gemma3_27bFree("google/gemma-3-27b-it:free"),
        Qwen3_235bFree("qwen/qwen3-235b-a22b:free"),
    }

    /** Escape hatch for ids not yet in [Known]. */
    data class Custom(override val id: String) : OpenRouterModel

    companion object {
        val Default: OpenRouterModel = Known.AutoFree

        /** Resolves a raw id to a [Known] entry, falling back to [Custom]. */
        fun fromId(id: String): OpenRouterModel =
            Known.entries.firstOrNull { it.id == id } ?: Custom(id)
    }
}
