package ru.den.writes.code.agenticHub.features.rag.chunking

private val TOKEN_REGEX = Regex("""\S+""")

/**
 * Token-based sliding-window chunking: group the document's whitespace-delimited
 * tokens into windows of [tokensPerChunk] tokens overlapping by [overlap] tokens
 * (the lecture's "cut every N tokens" method, with overlap). Unlike
 * [FixedSizeChunking], the cut always lands on a token boundary — a word is never
 * split across chunks. Each chunk's text is the original substring spanning its
 * first token's start to its last token's end, so punctuation and inner whitespace
 * within the window are preserved verbatim.
 *
 * A rough stand-in for real model tokens (words, not sub-word pieces), but the same
 * shape a token-precise tokenizer would take. Structure-blind: every chunk's
 * [ChunkMetadata.section] is `null`, [chunkId] is the 0-based emission ordinal. The
 * window advances by `tokensPerChunk - overlap` tokens; a document with fewer
 * tokens yields one chunk, a blank body yields none.
 *
 * @throws IllegalArgumentException if [tokensPerChunk] is not positive, or [overlap]
 *   is negative or not smaller than [tokensPerChunk] (a non-advancing window loops).
 */
public class TokenChunking(
    private val tokensPerChunk: Int,
    private val overlap: Int = 0,
) : ChunkingStrategy {
    init {
        require(tokensPerChunk > 0) { "tokensPerChunk must be positive, was $tokensPerChunk" }
        require(overlap >= 0) { "overlap must be non-negative, was $overlap" }
        require(overlap < tokensPerChunk) {
            "overlap ($overlap) must be smaller than tokensPerChunk ($tokensPerChunk)"
        }
    }

    override fun chunk(document: SourceDocument): List<Chunk> {
        val text = document.text
        val tokens = TOKEN_REGEX.findAll(text).toList()
        if (tokens.isEmpty()) return emptyList()

        val step = tokensPerChunk - overlap
        val chunks = mutableListOf<Chunk>()
        var start = 0
        var ordinal = 0
        while (start < tokens.size) {
            val endExclusive = minOf(start + tokensPerChunk, tokens.size)
            val from = tokens[start].range.first
            val to = tokens[endExclusive - 1].range.last + 1
            chunks += Chunk(
                text = text.substring(from, to),
                metadata = ChunkMetadata(
                    source = document.source,
                    title = document.title,
                    section = null,
                    chunkId = ordinal,
                ),
            )
            ordinal++
            if (endExclusive == tokens.size) break
            start += step
        }
        return chunks
    }
}
