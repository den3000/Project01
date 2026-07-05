package ru.den.writes.code.agenticHub.features.llm

import ru.den.writes.code.agenticHub.features.rag.indexing.ScoredChunk

/**
 * The rag→llm bridge: turn retrieved [ScoredChunk]s into a single grounding [Message]
 * (a `SYSTEM` turn) that instructs the model to answer from the supplied context and
 * cite the `[source: …]` tags — the "combine retrieved chunks with the question" step
 * of a RAG turn. Lives in `commonMain` because both the live tests and (later) the
 * two-mode agent orchestration need it; `ScoredChunk` in the signature is why llm
 * carries `api(features:rag)`.
 */
public fun ragChunksToContextMessage(chunks: List<ScoredChunk>): Message {
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
