package ru.den.writes.code.agenticHub.features.rag

/**
 * Turns a [SourceDocument] into an ordered list of [Chunk]s. Pure — no I/O, no
 * embedding — so strategies are trivially testable and comparable (see
 * [ChunkingComparison]). Two implementations ship: [FixedSizeChunking] (blind,
 * overlapping character windows) and [StructuralChunking] (markdown headings).
 */
public fun interface ChunkingStrategy {
    public fun chunk(document: SourceDocument): List<Chunk>
}
