package ru.den.writes.code.agenticHub.features.rag

import kotlinx.serialization.Serializable

/**
 * A [Chunk] paired with its embedding — the unit stored in a [VectorIndex].
 * Serializable so a built index round-trips through JSON persistence.
 */
@Serializable
public data class IndexedChunk(
    val chunk: Chunk,
    val embedding: List<Float>,
)

/**
 * A [Chunk] with the cosine [score] it earned against a query. Not persisted — it's
 * a transient search result (a retrieved chunk plus how relevant it was).
 */
public data class ScoredChunk(
    val chunk: Chunk,
    val score: Double,
)

/**
 * The searchable index: embedded chunks plus brute-force cosine search over them.
 * Linear scan — fine for the local, small-corpus scope here; an ANN backend (FAISS
 * et al.) would slot in behind the same [search] shape if the corpus grew.
 * Serializable so the whole index persists as one JSON document.
 */
@Serializable
public data class VectorIndex(
    val chunks: List<IndexedChunk>,
) {
    /**
     * Top-[topK] chunks by descending cosine similarity to [query]. Ties keep index
     * order (stable sort). [topK] `<= 0` yields nothing; a [topK] larger than the
     * index returns every chunk. An empty index returns nothing.
     */
    public fun search(query: List<Float>, topK: Int): List<ScoredChunk> {
        if (topK <= 0) return emptyList()
        return chunks
            .map { ScoredChunk(it.chunk, cosineSimilarity(query, it.embedding)) }
            .sortedByDescending { it.score }
            .take(topK)
    }
}
