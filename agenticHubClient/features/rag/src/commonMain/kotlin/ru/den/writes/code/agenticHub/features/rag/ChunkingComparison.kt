package ru.den.writes.code.agenticHub.features.rag

import kotlin.math.round

/**
 * Size metrics for one strategy's output over a document: how many [chunkCount]
 * chunks it produced and the character-length spread ([minChars]/[maxChars]/
 * [avgChars]). All zero for a strategy that emitted nothing.
 */
public data class ChunkingStats(
    val strategyName: String,
    val chunkCount: Int,
    val minChars: Int,
    val maxChars: Int,
    val avgChars: Double,
)

/**
 * Side-by-side [ChunkingStats] for several strategies run over the same
 * [documentTitle], in the order the strategies were supplied. Materializes the
 * "compare fixed-size vs structural chunking" step as data rather than prose.
 */
public data class ChunkingComparisonReport(
    val documentTitle: String,
    val stats: List<ChunkingStats>,
)

/**
 * Runs each named [ChunkingStrategy] over one document and collects size metrics,
 * so a caller can eyeball how differently the strategies slice the same text. Pure
 * — just chunks and counts, no embedding or I/O.
 */
public object ChunkingComparison {
    public fun compare(
        document: SourceDocument,
        strategies: Map<String, ChunkingStrategy>,
    ): ChunkingComparisonReport =
        ChunkingComparisonReport(
            documentTitle = document.title,
            stats = strategies.map { (name, strategy) ->
                statsFor(name, strategy.chunk(document))
            },
        )

    private fun statsFor(name: String, chunks: List<Chunk>): ChunkingStats {
        val sizes = chunks.map { it.text.length }
        return ChunkingStats(
            strategyName = name,
            chunkCount = chunks.size,
            minChars = sizes.minOrNull() ?: 0,
            maxChars = sizes.maxOrNull() ?: 0,
            avgChars = if (sizes.isEmpty()) 0.0 else sizes.average(),
        )
    }
}

/** Human-readable one-block summary of a [ChunkingComparisonReport]. */
public fun ChunkingComparisonReport.render(): String = buildString {
    appendLine("Chunking comparison for \"$documentTitle\":")
    stats.forEach {
        appendLine(
            "  ${it.strategyName}: ${it.chunkCount} chunks " +
                "(chars min=${it.minChars} max=${it.maxChars} avg=${round(it.avgChars).toInt()})"
        )
    }
}
