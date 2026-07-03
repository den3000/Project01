package ru.den.writes.code.agenticHub.features.rag

/**
 * Builds a [VectorIndex] from raw documents: chunk every document with [chunking],
 * embed all chunk texts in one batched [Embedder] call, and pair each chunk with
 * its vector. The whole "documents → searchable index" half of the RAG pipeline.
 *
 * Chunking strategy and embedder are injected, so the same pipeline compares
 * strategies or swaps the embedder (fake ↔ Ollama) without changing here.
 */
public class IndexingPipeline(
    private val chunking: ChunkingStrategy,
    private val embedder: Embedder,
) {
    public suspend fun index(documents: List<SourceDocument>): VectorIndex {
        val chunks = documents.flatMap { chunking.chunk(it) }
        if (chunks.isEmpty()) return VectorIndex(emptyList())
        val vectors = embedder.embed(chunks.map { it.text })
        return VectorIndex(chunks.zip(vectors) { chunk, vector -> IndexedChunk(chunk, vector) })
    }
}
