package ru.den.writes.code.agenticHub.features.llm

import ru.den.writes.code.agenticHub.features.rag.indexing.ScoredChunk
import ru.den.writes.code.agenticHub.features.rag.rerank.Reranker

/**
 * A CrossEncoder-style [Reranker] backed by an [LlmApi]: for every candidate it asks
 * the model, in isolation, how well the passage *answers* the [query] (a score in
 * `[0.0, 1.0]`), then drops everything below [threshold] and keeps the best
 * [topKAfter]. Unlike the retrieval cosine — the same embedding signal that surfaced
 * these candidates — the model reads passage and question jointly, so it can push
 * down a chunk that is on-topic yet doesn't actually answer. Slower (one model call
 * per candidate) but sharper; this is why it lives in features:llm, not rag.
 *
 * Each surviving [ScoredChunk] carries the model relevance in place of the original
 * cosine. Ties keep input order (stable sort); `topKAfter <= 0` yields nothing — same
 * shape as [LexicalReranker][ru.den.writes.code.agenticHub.features.rag.rerank.LexicalReranker].
 * A model reply that carries no parseable number scores `0.0` (treated as irrelevant).
 */
public class ModelReranker(
    private val llmApi: LlmApi,
    private val threshold: Double = 0.5,
    private val topKAfter: Int = 3,
    private val params: GenerationParams = GenerationParams(temperature = 0.0, maxTokens = 10, thinkingBudget = 0),
) : Reranker {
    override suspend fun rerank(query: String, candidates: List<ScoredChunk>): List<ScoredChunk> {
        if (topKAfter <= 0) return emptyList()
        return candidates
            .map { it.copy(score = scorePassage(query, it.chunk.text)) }
            .filter { it.score >= threshold }
            .sortedByDescending { it.score }
            .take(topKAfter)
    }

    private suspend fun scorePassage(query: String, passage: String): Double {
        val messages = listOf(
            Message(Role.SYSTEM, SCORE_INSTRUCTION),
            Message(Role.USER, "Question: $query\n\nPassage:\n$passage"),
        )
        return llmApi.send(messages, params).text?.let(::parseScore) ?: 0.0
    }

    private fun parseScore(reply: String): Double =
        NUMBER.find(reply)?.value?.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.0

    private companion object {
        const val SCORE_INSTRUCTION =
            "You are a relevance grader for retrieval. Given a question and a passage, output how " +
                "well the passage ANSWERS the question as a single number between 0 and 1. Score " +
                "close to 1 ONLY if the passage states the specific answer the question asks for " +
                "(a concrete value, number, date, day, or name). Score close to 0 if the passage " +
                "is merely on the same topic — or only says where the answer is defined — WITHOUT " +
                "stating it. Reply with ONLY the number."

        // First bare number in the reply (e.g. "0.85", "1", "0"); coerced into [0,1] by the caller.
        val NUMBER = Regex("""\d+(?:\.\d+)?""")
    }
}
