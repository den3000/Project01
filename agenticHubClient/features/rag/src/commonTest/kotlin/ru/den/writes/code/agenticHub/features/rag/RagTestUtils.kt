package ru.den.writes.code.agenticHub.features.rag

import ru.den.writes.code.agenticHub.features.rag.chunking.SourceDocument

/**
 * Shared [SourceDocument] factory for the RAG tests: sensible defaults for the
 * [source]/[title] a test rarely cares about, so a test names only the body [text]
 * that matters to it.
 */
internal fun doc(
    text: String,
    source: String = "src",
    title: String = "title",
): SourceDocument = SourceDocument(source = source, title = title, text = text)
