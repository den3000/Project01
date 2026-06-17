package ru.den.writes.code.project01.shared.llm

/**
 * Typed Hugging Face Inference Providers model identifier.
 *
 * Mirrors [GeminiModel] / [OpenRouterModel] in shape: a small [Known]
 * catalog plus a [Custom] escape hatch. Ids on the wire follow HF Hub's
 * `org/repo` convention (e.g. `meta-llama/Llama-3.3-70B-Instruct`) and
 * are forwarded verbatim to the HF Router endpoint.
 *
 * The Router rotates available models as backing providers come and go;
 * every id below was verified live against
 * `https://router.huggingface.co/v1/models` in June 2026, but expect
 * drift — refresh the catalog by hand when one starts 404-ing. Any id
 * not in [Known] still works via `-model org/repo` → [Custom].
 *
 * Default is [Known.Llama33_70bInstruct] — a proven open-weight general
 * model with broad provider coverage on the Router (so cold-start is
 * less of a concern than for niche reasoning variants).
 */
sealed interface HuggingFaceModel {
    val id: String

    /**
     * A handful of stable, currently-routed models picked for breadth:
     * one general-purpose Llama, one reasoning model, one small
     * thinking model, one fresh Qwen MoE, and one large open-weight
     * general.
     */
    enum class Known(override val id: String) : HuggingFaceModel {
        Llama33_70bInstruct("meta-llama/Llama-3.3-70B-Instruct"),
        DeepSeekR1("deepseek-ai/DeepSeek-R1"),
        Qwen3_4bThinking("Qwen/Qwen3-4B-Thinking-2507"),
        Qwen36_35bA3b("Qwen/Qwen3.6-35B-A3B"),
        GptOss120b("openai/gpt-oss-120b"),
    }

    /** Escape hatch for ids not yet in [Known]. */
    data class Custom(override val id: String) : HuggingFaceModel

    companion object {
        val Default: HuggingFaceModel = Known.Llama33_70bInstruct

        /** Resolves a raw id to a [Known] entry, falling back to [Custom]. */
        fun fromId(id: String): HuggingFaceModel =
            Known.entries.firstOrNull { it.id == id } ?: Custom(id)
    }
}
