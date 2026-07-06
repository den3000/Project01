package ru.den.writes.code.agenticHub.features.rag

import ru.den.writes.code.agenticHub.features.rag.chunking.ChunkingStrategy
import ru.den.writes.code.agenticHub.features.rag.chunking.SourceDocument
import ru.den.writes.code.agenticHub.features.rag.embedding.Embedder
import ru.den.writes.code.agenticHub.features.rag.indexing.IndexStore
import ru.den.writes.code.agenticHub.features.rag.indexing.IndexingPipeline

/**
 * One-call "index a document to disk under a name": chunk + embed [document] with
 * [chunking] and [embedder], then persist the built [VectorIndex] to [path] via
 * [IndexStore]. The thin glue an admin command (`-rag add <name> src <file>`) needs
 * so the CLI layer stays out of the pipeline/store wiring. Returns the chunk count.
 *
 * The [embedder] is passed per call (the caller picks Ollama vs Gemini per
 * `-rag add … embedder <…>`), so index build and later query can be kept on the same
 * backend. Path/name resolution stays with the caller — this only knows "build the
 * index, write it here".
 */
public class RagIndexer(
    private val indexStore: IndexStore,
) {
    public suspend fun index(document: SourceDocument, path: String, chunking: ChunkingStrategy, embedder: Embedder): Int {
        val index = IndexingPipeline(chunking, embedder).index(listOf(document))
        indexStore.save(index, path)
        return index.chunks.size
    }
}
