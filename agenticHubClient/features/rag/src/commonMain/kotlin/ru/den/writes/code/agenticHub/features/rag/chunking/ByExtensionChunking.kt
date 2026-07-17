package ru.den.writes.code.agenticHub.features.rag.chunking

/**
 * Routes each document to the [ChunkingStrategy] registered for its file extension,
 * falling back to [default].
 *
 * A corpus that mixes prose and code needs a different cut per format — markdown by
 * headings ([StructuralChunking]), code by token windows ([TokenChunking]) — but
 * [IndexingPipeline][ru.den.writes.code.agenticHub.features.rag.indexing.IndexingPipeline]
 * holds one strategy for the whole corpus. Since [ChunkingStrategy.chunk] is handed the
 * document, a router can itself be a strategy: one index, per-format cutting, no change
 * to the pipeline.
 *
 * The extension is read off [SourceDocument.source] (the corpus puts the file path
 * there), taken after the last `/` so a dotted directory name can't be mistaken for one,
 * and lowercased. A source with no extension, or one absent from [byExtension], goes to
 * [default].
 */
public class ByExtensionChunking(
    private val default: ChunkingStrategy,
    private val byExtension: Map<String, ChunkingStrategy>,
) : ChunkingStrategy {
    override fun chunk(document: SourceDocument): List<Chunk> =
        strategyFor(document.source).chunk(document)

    private fun strategyFor(source: String): ChunkingStrategy {
        val fileName = source.substringAfterLast('/')
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return byExtension[extension] ?: default
    }
}
