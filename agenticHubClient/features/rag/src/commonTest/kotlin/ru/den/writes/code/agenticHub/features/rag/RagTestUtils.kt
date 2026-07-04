package ru.den.writes.code.agenticHub.features.rag

import ru.den.writes.code.agenticHub.features.rag.chunking.Chunk
import ru.den.writes.code.agenticHub.features.rag.chunking.ChunkMetadata
import ru.den.writes.code.agenticHub.features.rag.chunking.SourceDocument
import ru.den.writes.code.agenticHub.features.rag.indexing.IndexedChunk
import ru.den.writes.code.agenticHub.features.rag.indexing.VectorIndex

/**
 * Shared [SourceDocument] factory for the RAG tests: sensible defaults for the
 * [source]/[title] a test rarely cares about, so a test names only the body [text]
 * that matters to it.
 */
internal fun doc(
    text: String,
    source: String = "src",
    title: String = "title",
): SourceDocument = SourceDocument(source = source, title = title, text = text)

// File name shared by both fixtures (source == title here).
internal const val KB_FILE = "kb.md"

// --- knowledgeDoc: a two-section markdown KB for retrieval/indexing tests. The
// section names are single-sourced so a test can assert against the same constant
// StructuralChunking will parse out of the "# heading" line.
internal const val VECTOR_SEARCH_SECTION = "Vector search"
internal const val GARDENING_SECTION = "Gardening"

internal fun knowledgeDoc(): SourceDocument = doc(
    source = KB_FILE,
    title = KB_FILE,
    text = "# $VECTOR_SEARCH_SECTION\n" +
        "embeddings and cosine similarity power vector search over documents\n\n" +
        "# $GARDENING_SECTION\n" +
        "tomatoes need sunlight water and rich soil to grow well",
)

// --- sampleIndex: a minimal one-chunk index for persistence/graph tests. Its
// metadata values are single-sourced so a round-trip test can assert against them.
internal const val SAMPLE_SECTION = "Intro"
internal const val SAMPLE_INDEX_PATH = "indexes/kb.json"

internal fun sampleIndex(): VectorIndex = VectorIndex(
    listOf(
        IndexedChunk(
            chunk = Chunk(
                text = "hello world",
                metadata = ChunkMetadata(
                    source = KB_FILE,
                    title = KB_FILE,
                    section = SAMPLE_SECTION,
                    chunkId = 0,
                ),
            ),
            embedding = listOf(0.1f, 0.2f, 0.3f),
        ),
    ),
)
