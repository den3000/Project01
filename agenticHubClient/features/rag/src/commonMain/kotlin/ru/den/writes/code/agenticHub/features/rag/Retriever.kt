package ru.den.writes.code.agenticHub.features.rag

/**
 * The query half of the RAG pipeline: embed a natural-language [query] with the
 * same [Embedder] the index was built with, then rank the [index] by cosine
 * similarity and return the top matches. The [Embedder] MUST match the one used to
 * build [index] — vectors from different models aren't comparable.
 */
public class Retriever(
    private val embedder: Embedder,
    private val index: VectorIndex,
) {
    public suspend fun retrieve(query: String, topK: Int): List<ScoredChunk> {
        val queryVector = embedder.embed(listOf(query)).single()
        return index.search(queryVector, topK)
    }
}
