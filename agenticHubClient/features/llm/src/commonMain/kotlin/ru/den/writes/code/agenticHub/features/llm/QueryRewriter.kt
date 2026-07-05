package ru.den.writes.code.agenticHub.features.llm

/**
 * Pre-retrieval query rewriting: turn a user's raw question into a form that embeds
 * and retrieves better (expand abbreviations, surface key nouns/synonyms, drop
 * conversational filler) before it ever reaches the vector index. A no-op
 * ([Identity]) leaves the query untouched — the baseline to compare against.
 */
public fun interface QueryRewriter {
    public suspend fun rewrite(query: String): String

    public companion object {
        /** Pass-through rewriter — the "no rewriting" baseline. */
        public val Identity: QueryRewriter = QueryRewriter { it }
    }
}

/**
 * [QueryRewriter] backed by an [LlmApi]: asks the model to rephrase [query] into a
 * concise retrieval query. Deterministic knobs by default (temperature 0, no
 * thinking, short cap). Falls back to the original query whenever the model errors
 * or returns nothing — rewriting must never make retrieval worse by dropping the
 * question on the floor.
 */
public class ModelQueryRewriter(
    private val llmApi: LlmApi,
    private val params: GenerationParams = GenerationParams(temperature = 0.0, maxTokens = 100, thinkingBudget = 0),
) : QueryRewriter {
    override suspend fun rewrite(query: String): String {
        val messages = listOf(
            Message(Role.SYSTEM, REWRITE_INSTRUCTION),
            Message(Role.USER, query),
        )
        return llmApi.send(messages, params).text?.trim()?.takeIf { it.isNotBlank() } ?: query
    }

    private companion object {
        const val REWRITE_INSTRUCTION =
            "Rewrite the user's question into a concise search query for retrieval over a " +
                "knowledge base. Expand abbreviations, keep the key nouns, and add close synonyms " +
                "of the main terms. Reply with ONLY the rewritten query — no preamble, no quotes."
    }
}
