package ru.den.writes.code.project01.mcpLab

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * The accumulated weather report — the middle and final stages of the MCP pipeline
 * (`current_weather` → `add_to_report` → `save_to_file`). [ReportStore] holds the entries
 * in memory for the lifetime of one server session; the pure [renderReport] / [reportFileFor]
 * / [saveReport] helpers do the formatting, path resolution and writing, so they can be
 * tested without a live server.
 */

/** Markdown rendering of the report: a header plus one bullet per entry; empty → a placeholder. */
fun renderReport(entries: List<String>): String =
    if (entries.isEmpty()) {
        "# Weather report\n\n(empty report)"
    } else {
        "# Weather report\n\n" + entries.joinToString("\n") { "- $it" }
    }

/** Where saved reports live: a `reports/` dir next to the scheduler's `schedule.json`. */
fun reportsDir(): File = File(System.getProperty("user.home"), ".project01-mcplab/reports")

/**
 * Resolve a `save_to_file` filename to a path under [reportsDir]. Only the base name is
 * kept (`File(name).name` strips any directory or `..`), so the tool can't write outside
 * the reports dir; a blank or missing name falls back to `report.md`.
 */
fun reportFileFor(name: String?): File {
    val base = name?.let { File(it).name }?.takeIf { it.isNotBlank() } ?: "report.md"
    return File(reportsDir(), base)
}

/** Write [content] to [file], creating the parent dir if needed. */
fun saveReport(file: File, content: String) {
    file.parentFile?.mkdirs()
    file.writeText(content)
}

/** In-memory, session-scoped accumulator for the report entries; guarded by a [Mutex]. */
class ReportStore {
    private val mutex = Mutex()
    private val entries = mutableListOf<String>()

    /** Append [text] and return the new entry count. */
    suspend fun add(text: String): Int = mutex.withLock {
        entries += text
        entries.size
    }

    /** A copy of the current entries. */
    suspend fun snapshot(): List<String> = mutex.withLock { entries.toList() }

    /** The rendered report for the current entries. */
    suspend fun render(): String = renderReport(snapshot())
}
