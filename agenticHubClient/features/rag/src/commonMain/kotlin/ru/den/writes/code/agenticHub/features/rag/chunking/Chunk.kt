package ru.den.writes.code.agenticHub.features.rag.chunking

import kotlinx.serialization.Serializable

/**
 * Provenance carried by every [Chunk] so a retrieved fragment can be traced and
 * cited: [source] and [title] copied from the origin [SourceDocument], [section]
 * naming the structural block it came from (a markdown heading for
 * [StructuralChunking], `null` when the strategy is structure-blind), and
 * [chunkId] — the 0-based ordinal of the chunk within its document, assigned in
 * emission order by the chunking strategy. Serializable so a built index
 * round-trips through JSON.
 */
@Serializable
public data class ChunkMetadata(
    val source: String,
    val title: String,
    val section: String?,
    val chunkId: Int,
)

/**
 * A slice of a [SourceDocument] ready to be embedded and indexed: the [text] to
 * vectorize plus its [metadata].
 */
@Serializable
public data class Chunk(
    val text: String,
    val metadata: ChunkMetadata,
)
