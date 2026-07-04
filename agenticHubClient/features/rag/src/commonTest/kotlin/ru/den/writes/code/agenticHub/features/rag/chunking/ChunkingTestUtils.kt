package ru.den.writes.code.agenticHub.features.rag.chunking

/**
 * Shared [SourceDocument] factory for the strategy/pipeline tests: sensible defaults
 * for the [source]/[title] a chunking test rarely cares about, so a test names only
 * the body [text] that matters to it.
 */
internal fun doc(
    text: String,
    source: String = "src",
    title: String = "title",
): SourceDocument = SourceDocument(source = source, title = title, text = text)
