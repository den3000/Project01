package ru.den.writes.code.agenticHub.features.rag.rerank

import ru.den.writes.code.agenticHub.features.rag.indexing.ScoredChunk

/**
 * Offline, deterministic [Reranker] scored by lexical overlap: how many distinct
 * query terms appear in a candidate's text, divided by the number of query terms
 * (query recall, in `[0.0, 1.0]`). A different, cheaper signal than the retrieval
 * cosine — it rewards candidates that literally contain the asked-about terms, which
 * helps push a topically-similar-but-non-answering chunk down.
 *
 * The pipeline is: re-score every candidate, drop those below [threshold] (the cutoff
 * for "not relevant enough"), sort by the new score descending, and keep at most
 * [topKAfter] (the top-K *after* filtering). Ties keep input order (stable sort),
 * mirroring [VectorIndex.search][ru.den.writes.code.agenticHub.features.rag.indexing.VectorIndex.search];
 * `topKAfter <= 0` yields nothing. Each surviving [ScoredChunk] carries its lexical
 * score in place of the original cosine, so downstream sees the rerank signal.
 *
 * Both knobs are tunable per call site; the defaults ([threshold] `= 0.0` keeps every
 * candidate, [topKAfter] `= 5`) make the reranker a pure reorder-and-cap out of the box.
 */
public class LexicalReranker(
    private val threshold: Double = 0.0,
    private val topKAfter: Int = 5,
) : Reranker {
    override suspend fun rerank(query: String, candidates: List<ScoredChunk>): List<ScoredChunk> {
        if (topKAfter <= 0) return emptyList()
        val queryTerms = terms(query)
        return candidates
            .map { it.copy(score = lexicalScore(queryTerms, it.chunk.text)) }
            .filter { it.score >= threshold }
            .sortedByDescending { it.score }
            .take(topKAfter)
    }

    private fun lexicalScore(queryTerms: Set<String>, text: String): Double {
        if (queryTerms.isEmpty()) return 0.0
        val textTerms = terms(text)
        val overlap = queryTerms.count { it in textTerms }
        return overlap.toDouble() / queryTerms.size
    }

    private fun terms(text: String): Set<String> =
        text.lowercase().split(NON_WORD).filterTo(mutableSetOf()) { it.isNotBlank() }

    private companion object {
        // Split on any run of non-alphanumeric characters → bare word tokens.
        val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    }
}
