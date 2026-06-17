package ru.den.writes.code.project01.shared.llm.gemini

/**
 * Typed Gemini model identifier.
 *
 * Replaces a raw `String?` so typos surface at compile time for known
 * models and CLI parsing routes through one place ([fromId]). [Custom]
 * keeps the door open for any model id the catalog hasn't been updated
 * for yet — Gemini will still receive the literal string in the URL.
 */
sealed interface GeminiModel {
    val id: String

    /**
     * Catalog of text-only Gemini models as of June 2026.
     *
     * Note: `gemini-3-flash-preview` is «Gemini 3.1 Flash» — without the
     * `.1`. There is no `gemini-3.5-pro` and no `gemini-3.5-flash-lite`.
     */
    enum class Known(override val id: String) : GeminiModel {
        Gemini25Pro("gemini-2.5-pro"),
        Gemini25Flash("gemini-2.5-flash"),
        Gemini25FlashLite("gemini-2.5-flash-lite"),
        Gemini31ProPreview("gemini-3.1-pro-preview"),
        Gemini3FlashPreview("gemini-3-flash-preview"),
        Gemini31FlashLite("gemini-3.1-flash-lite"),
        Gemini35Flash("gemini-3.5-flash"),
    }

    /** Escape hatch for ids not yet in [Known]. */
    data class Custom(override val id: String) : GeminiModel

    companion object {
        val Default: GeminiModel = Known.Gemini25Flash

        /** Resolves a raw id to a [Known] entry, falling back to [Custom]. */
        fun fromId(id: String): GeminiModel =
            Known.entries.firstOrNull { it.id == id } ?: Custom(id)
    }
}