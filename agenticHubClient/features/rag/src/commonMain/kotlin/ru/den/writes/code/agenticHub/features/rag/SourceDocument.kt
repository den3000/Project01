package ru.den.writes.code.agenticHub.features.rag

/**
 * One document fed into the indexing pipeline before chunking.
 *
 * [source] is a stable locator (file path / URI) copied onto every chunk's
 * metadata so a retrieved chunk can be cited back to its origin; [title] is a
 * human-friendly name (usually the file name) and [text] is the full UTF-8 body.
 */
public data class SourceDocument(
    val source: String,
    val title: String,
    val text: String,
)
