package ru.den.writes.code.agenticHub.features.rag.chunking

private val HEADING_REGEX = Regex("""^#{1,6}\s+(.*\S)\s*$""")

/**
 * Structure-aware chunking: split a markdown body at its ATX headings (`#`..`######`)
 * into one chunk per section, so a chunk stays a coherent semantic block instead of
 * a blind character window. The heading line is kept at the top of its section's
 * [Chunk.text] (useful context for the embedder) and its title lands in
 * [ChunkMetadata.section].
 *
 * MVP: one chunk per section — an oversized section is emitted whole, not sub-split
 * (a size cap can layer [FixedSizeChunking] on top later). Text before the first
 * heading becomes a `section = null` preamble chunk; a blank preamble is dropped.
 * A document with no headings yields a single `section = null` chunk. [chunkId] is
 * the 0-based ordinal across emitted chunks.
 */
public class StructuralChunking : ChunkingStrategy {
    override fun chunk(document: SourceDocument): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        val buffer = StringBuilder()
        var section: String? = null
        var ordinal = 0

        fun flush() {
            val body = buffer.toString().trim()
            if (body.isNotEmpty()) {
                chunks += Chunk(
                    text = body,
                    metadata = ChunkMetadata(
                        source = document.source,
                        title = document.title,
                        section = section,
                        chunkId = ordinal,
                    ),
                )
                ordinal++
            }
            buffer.clear()
        }

        for (line in document.text.split("\n")) {
            val heading = HEADING_REGEX.matchEntire(line.trimStart())?.groupValues?.get(1)
            if (heading != null) {
                flush()
                section = heading
            }
            buffer.appendLine(line)
        }
        flush()
        return chunks
    }
}
