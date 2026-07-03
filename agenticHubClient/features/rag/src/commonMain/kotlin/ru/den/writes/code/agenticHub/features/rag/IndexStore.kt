package ru.den.writes.code.agenticHub.features.rag

import kotlinx.serialization.json.Json
import ru.den.writes.code.agenticHub.platform.filesystem.LocalFileSystem

/**
 * Persists a [VectorIndex] as a single JSON document through the [LocalFileSystem]
 * port, so a built index survives across runs and need not be re-embedded every
 * time. JSON keeps the on-disk index human-inspectable and portable (the lecture's
 * "FAISS / SQLite / JSON" options — JSON here); the filesystem port keeps the store
 * platform-neutral. The embedder/chunker choices are the caller's to keep
 * consistent — the file records vectors, not which model produced them.
 */
public class IndexStore(
    private val fs: LocalFileSystem,
    private val json: Json = Json,
) {
    /** Overwrite [path] with the JSON encoding of [index]. */
    public fun save(index: VectorIndex, path: String) {
        fs.writeText(path, json.encodeToString(VectorIndex.serializer(), index))
    }

    /** Decode the index at [path], or `null` if no file exists there. */
    public fun load(path: String): VectorIndex? {
        val text = fs.readText(path) ?: return null
        return json.decodeFromString(VectorIndex.serializer(), text)
    }
}
