package ru.den.writes.code.project01.mcps.localfs

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * The in-memory document this server composes and writes to disk — the building
 * blocks for the `append_to_document` / `save_document` tools. [DocumentStore] holds
 * the lines for the lifetime of one server session; the pure [renderDocument] /
 * [documentFileFor] / [saveDocument] helpers do the formatting, path resolution and
 * writing, so they can be tested without a live server. Generic on purpose: callers
 * decide what each line means (a weather summary, a note, anything).
 */

/** The document body: one appended line per entry, joined by newlines (empty → ""). */
fun renderDocument(lines: List<String>): String = lines.joinToString("\n")

/** Where saved documents live: `~/.project01-localfs/documents`. */
fun documentsDir(): File = File(System.getProperty("user.home"), ".project01-localfs/documents")

/**
 * Resolve a `save_document` filename to a path under [documentsDir]. Only the base
 * name is kept (`File(name).name` strips any directory or `..`), so the tool can't
 * write outside the documents dir; a blank or missing name falls back to `document.md`.
 */
fun documentFileFor(name: String?): File {
    val base = name?.let { File(it).name }?.takeIf { it.isNotBlank() } ?: "document.md"
    return File(documentsDir(), base)
}

/** Write [content] to [file], creating the parent dir if needed. */
fun saveDocument(file: File, content: String) {
    file.parentFile?.mkdirs()
    file.writeText(content)
}

/** In-memory, session-scoped accumulator for the document lines; guarded by a [Mutex]. */
class DocumentStore {
    private val mutex = Mutex()
    private val lines = mutableListOf<String>()

    /** Append [text] and return the new line count. */
    suspend fun add(text: String): Int = mutex.withLock {
        lines += text
        lines.size
    }

    /** A copy of the current lines. */
    suspend fun snapshot(): List<String> = mutex.withLock { lines.toList() }

    /** The rendered document for the current lines. */
    suspend fun render(): String = renderDocument(snapshot())
}
