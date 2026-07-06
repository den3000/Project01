package ru.den.writes.code.agenticHub.features.llm

import ru.den.writes.code.agenticHub.features.rag.indexing.ScoredChunk

/**
 * Turns retrieved [ScoredChunk]s into a [GroundedAnswer], enforcing the relevance gate in code — not
 * just hoping the prompt holds. If there are no chunks, or the best chunk's score is below
 * [relevanceThreshold], it returns a "don't know, please clarify" answer WITHOUT calling the model
 * (cheap and truly enforced). Above the threshold it asks the model for a cited JSON answer via
 * [groundedAnswerPrompt], parses it with [parseGroundedAnswer], and re-anchors every citation to the
 * real chunk it was quoted from via [groundedIn] (dropping any quote the corpus doesn't contain).
 *
 * [relevanceThreshold] is compared against whatever score the chunks carry — cosine from the
 * [Retriever][ru.den.writes.code.agenticHub.features.rag.Retriever], or a reranker's 0..1 relevance —
 * so pick it to match the stage feeding this answerer.
 */
public class GroundedAnswerer(
    private val llmApi: LlmApi,
    private val relevanceThreshold: Double,
    private val params: GenerationParams = GenerationParams(temperature = 0.0, maxTokens = 400, thinkingBudget = 0),
) {
    public suspend fun answer(question: String, chunks: List<ScoredChunk>): GroundedAnswer {
        val top = chunks.maxByOrNull { it.score }
        if (top == null || top.score < relevanceThreshold) {
            return GroundedAnswer(answer = DONT_KNOW, citations = emptyList(), isKnown = false)
        }
        val reply = llmApi.send(groundedAnswerPrompt(question, chunks), params)
        return parseGroundedAnswer(reply.text.orEmpty()).groundedIn(chunks)
    }

    private companion object {
        const val DONT_KNOW =
            "I don't have relevant enough context to answer that confidently. " +
                "Could you clarify or rephrase your question?"
    }
}
