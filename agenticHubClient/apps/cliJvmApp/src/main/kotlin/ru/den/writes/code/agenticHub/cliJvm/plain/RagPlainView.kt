package ru.den.writes.code.agenticHub.cliJvm.plain

import ru.den.writes.code.agenticHub.features.lifecycle.session.ragSourceLines
import ru.den.writes.code.agenticHub.features.rag.indexing.ScoredChunk

/**
 * The RAG sources for a turn as stderr lines — `[rag] sources:` then one
 * `[source › section #id] score=…` per retrieved chunk. Metadata about the
 * answer, so it goes to stderr like `[session]` / `[task]`, keeping stdout the
 * pure reply.
 */
internal data class RagPlainView(val chunks: List<ScoredChunk>) : PlainView {
    override fun stderr(): List<String> = ragSourceLines(chunks)
}
