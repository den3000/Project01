package ru.den.writes.code.project01.scheduling

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A JSON-file-backed [ScheduleStore]. Writes atomically (temp file + atomic rename) so a
 * crash mid-write never leaves a half-written file. A missing or corrupt file reads back
 * as empty state — startup never throws on it. The parent directory is created on first
 * write. The caller supplies the path (e.g. `~/.project01-cli/scheduler.json`); no default
 * location is baked in. Tasks and results share one file.
 */
class JsonFileScheduleStore(private val file: File) : ScheduleStore {

    override fun loadTasks(): List<ScheduledTask> = read().tasks
    override fun loadResults(): List<TaskResult> = read().results

    override fun saveTasks(tasks: List<ScheduledTask>) {
        write(read().copy(tasks = tasks))
    }

    override fun appendResult(result: TaskResult) {
        val state = read()
        write(state.copy(results = state.results + result))
    }

    private fun read(): PersistedState {
        if (!file.exists()) return PersistedState()
        return runCatching { json.decodeFromString<PersistedState>(file.readText()) }
            .getOrDefault(PersistedState())
    }

    private fun write(state: PersistedState) {
        val dir = file.absoluteFile.parentFile
        dir.mkdirs()
        val tmp = File(dir, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(state))
        val src = tmp.toPath()
        val dst = file.toPath()
        try {
            Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }
    }
}

/** On-disk shape: tasks + results in one file. Private — an implementation detail. */
@Serializable
private data class PersistedState(
    val tasks: List<ScheduledTask> = emptyList(),
    val results: List<TaskResult> = emptyList(),
)
