package ru.den.writes.code.agenticHub.features.rag

/**
 * Fixed-size sliding-window chunking: cut the document body into [chunkSize]-char
 * windows that overlap by [overlap] chars, so a sentence split across a boundary
 * still appears whole in one of the neighbouring chunks (the "boundary on a lock"
 * trick — overlap keeps context from being lost at the seams). Character-based,
 * not token-based — deliberately simple; a token-aware variant can slot in behind
 * [ChunkingStrategy] later without touching callers.
 *
 * The window advances by `chunkSize - overlap` per step. Every chunk's
 * [ChunkMetadata.section] is `null` — this strategy is structure-blind by design.
 * A body shorter than [chunkSize] yields a single chunk; a blank body yields none.
 *
 * @throws IllegalArgumentException if [chunkSize] is not positive, or [overlap] is
 *   negative or not smaller than [chunkSize] (a non-advancing window would loop).
 */
public class FixedSizeChunking(
    private val chunkSize: Int,
    private val overlap: Int = 0,
) : ChunkingStrategy {
    init {
        require(chunkSize > 0) { "chunkSize must be positive, was $chunkSize" }
        require(overlap >= 0) { "overlap must be non-negative, was $overlap" }
        require(overlap < chunkSize) {
            "overlap ($overlap) must be smaller than chunkSize ($chunkSize)"
        }
    }

    override fun chunk(document: SourceDocument): List<Chunk> {
        val text = document.text
        if (text.isBlank()) return emptyList()

        val step = chunkSize - overlap
        val chunks = mutableListOf<Chunk>()
        var start = 0
        var ordinal = 0
        while (start < text.length) {
            val end = minOf(start + chunkSize, text.length)
            chunks += Chunk(
                text = text.substring(start, end),
                metadata = ChunkMetadata(
                    source = document.source,
                    title = document.title,
                    section = null,
                    chunkId = ordinal,
                ),
            )
            ordinal++
            if (end == text.length) break
            start += step
        }
        return chunks
    }
}
