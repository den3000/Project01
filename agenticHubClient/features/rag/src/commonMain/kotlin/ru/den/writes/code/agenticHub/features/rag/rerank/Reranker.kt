package ru.den.writes.code.agenticHub.features.rag.rerank

import ru.den.writes.code.agenticHub.features.rag.indexing.ScoredChunk

/**
 * The second stage of retrieval: re-score and filter the candidates a
 * [Retriever][ru.den.writes.code.agenticHub.features.rag.Retriever] surfaced, before
 * they reach the model. Vector search is fast but coarse — the top-K by cosine can
 * include chunks that are on-topic yet don't actually answer the [query]. A reranker
 * re-orders those candidates by a sharper relevance signal, drops the ones below a
 * cutoff, and keeps only the best few (top-K *after* filtering).
 *
 * Implementations differ by signal: an offline lexical one
 * ([LexicalReranker], term overlap) lives here in rag; a model-backed CrossEncoder
 * lives in features:llm (it needs an LLM, and rag must not depend on llm).
 *
 * `suspend` because a model-backed reranker does network I/O per candidate.
 */
public fun interface Reranker {
    public suspend fun rerank(query: String, candidates: List<ScoredChunk>): List<ScoredChunk>
}
