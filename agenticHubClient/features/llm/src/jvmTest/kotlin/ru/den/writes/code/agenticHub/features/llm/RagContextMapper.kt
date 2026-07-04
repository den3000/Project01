package ru.den.writes.code.agenticHub.features.llm

import ru.den.writes.code.agenticHub.features.rag.indexing.ScoredChunk

/**
 * The rag↔llm format bridge, kept in test scope on purpose: retrieval lives in
 * features:rag, generation in features:llm, and neither depends on the other. This
 * mapper turns retrieved [ScoredChunk]s into a single grounding [Message] (a `SYSTEM`
 * turn) that instructs the model to answer from the supplied context and cite the
 * `[source: …]` tags — the "combine retrieved chunks with the question" step of a RAG
 * turn. When the real two-mode agent is built, this logic moves up into the
 * orchestration layer (agent/CLI) that legitimately depends on both modules.
 */
internal fun ragChunksToContextMessage(chunks: List<ScoredChunk>): Message {
    val context = chunks.joinToString("\n\n") { scored ->
        val m = scored.chunk.metadata
        val section = m.section?.let { " › $it" } ?: ""
        "[source: ${m.source}$section #${m.chunkId}]\n${scored.chunk.text}"
    }
    return Message(
        role = Role.SYSTEM,
        text = "Answer the question using ONLY the context below. Cite the [source: …] tags you " +
            "relied on. If the context does not contain the answer, say you don't know.\n\n$context",
    )
}
