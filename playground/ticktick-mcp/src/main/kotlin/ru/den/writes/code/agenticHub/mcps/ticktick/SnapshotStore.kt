package ru.den.writes.code.agenticHub.mcps.ticktick

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persists the week's plan snapshot — the impure edge that lets `review_week` know what was
 * planned even after the official API stops returning those tasks (once completed). The Store
 * factoring keeps [TicktickReports] free of file I/O so its logic is unit-tested against an
 * in-memory map. Production impl is [FileSnapshotStore].
 */
internal interface SnapshotStore {
    /** The snapshot stored under [label], or null when none exists. */
    fun read(label: String): WeekSnapshot?

    /** Persists [snapshot], overwriting any previous snapshot with the same label. */
    fun write(snapshot: WeekSnapshot)
}

/** The set of tasks planned for a week — captured at week start, checked off at week end. */
@Serializable
internal data class WeekSnapshot(
    val label: String,
    val from: Long,
    val to: Long,
    val planned: List<PlannedTask> = emptyList(),
)

/** A planned task, addressed at review by [projectId] + [id] (the `/task` endpoint is project-scoped). */
@Serializable
internal data class PlannedTask(
    val id: String,
    val projectId: String,
    val title: String = "",
    val dueDate: String? = null,
)

/**
 * Production [SnapshotStore]: one JSON file per label under [root] (`snapshot-<label>.json`). The
 * label is sanitized into the file name so it can't escape [root]. The impure edge — unwrapped so
 * tests swap it for an in-memory map.
 */
internal class FileSnapshotStore(private val root: String) : SnapshotStore {

    override fun read(label: String): WeekSnapshot? {
        val file = File(root, fileName(label))
        if (!file.exists()) return null
        return JSON.decodeFromString(WeekSnapshot.serializer(), file.readText(Charsets.UTF_8))
    }

    override fun write(snapshot: WeekSnapshot) {
        File(root).mkdirs()
        File(root, fileName(snapshot.label))
            .writeText(PRETTY_JSON.encodeToString(WeekSnapshot.serializer(), snapshot), Charsets.UTF_8)
    }

    private fun fileName(label: String): String = "snapshot-${label.replace(Regex("[^A-Za-z0-9_-]"), "_")}.json"

    private companion object {
        private val JSON = Json { ignoreUnknownKeys = true }
        private val PRETTY_JSON = Json { prettyPrint = true; encodeDefaults = true }
    }
}
