package ru.den.writes.code.agenticHub.mcps.projectfs

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The project root every test resolves against. */
internal const val ROOT = "/repo"

/**
 * In-memory [FileIo] keyed by path relative to [root]; no real file I/O.
 *
 * [sizes] overrides the byte count for a path, so a "too large to read" case can be
 * staged without materialising a megabyte of text. A NUL in the content makes the path
 * sniff as binary, exactly as it would on disk.
 */
internal class FileIoFake(
    private val root: String = ROOT,
    files: Map<String, String> = emptyMap(),
    private val sizes: Map<String, Long> = emptyMap(),
) : FileIo {

    /** Mutable so a write is visible to the next read, the way a real filesystem behaves. */
    private val files = files.toMutableMap()

    /** What the fake currently holds — the `// then` side of a write test. */
    fun contentOf(rel: String): String? = files[rel]

    /**
     * Mirrors [RealFileIo.walk]: noise directories are not descended, so a `build/` path
     * never surfaces here either. A fake that returned them would let the filters
     * downstream grow a branch production can't reach — and a test proving that branch.
     */
    override fun walk(): List<String> =
        files.keys.filterNot { rel -> rel.split('/').any { it in NOISE_SEGMENTS } }

    override fun stat(absolute: String): FileStat? {
        val rel = relativeOf(absolute)
        val text = files[rel] ?: return null
        val bytes = size(absolute)
        if (bytes > LARGE_FILE_BYTES) return FileStat(bytes = bytes, lines = null)
        return FileStat(bytes = bytes, lines = text.countLines())
    }

    override fun size(absolute: String): Long {
        val rel = relativeOf(absolute)
        return sizes[rel] ?: files[rel]?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L
    }

    override fun read(absolute: String): String? = files[relativeOf(absolute)]

    override fun exists(absolute: String): Boolean = relativeOf(absolute) in files

    override fun write(absolute: String, text: String) {
        files[relativeOf(absolute)] = text
    }

    private fun relativeOf(absolute: String): String = absolute.removePrefix("$root/")
}

/**
 * Deterministic stand-in for `File.canonicalPath`: collapses `.` and `..` textually and
 * then applies [links] as symlink redirections.
 *
 * Injecting this is what lets the path gate be proven offline — a real symlink escaping
 * the root can't be staged in a unit test, but a redirection can.
 */
internal fun fakeCanonicalizer(links: Map<String, String> = emptyMap()): (String) -> String = { raw ->
    val collapsed = collapsePath(raw)
    val link = links.entries.firstOrNull { (from, _) -> collapsed == from || collapsed.startsWith("$from/") }
    if (link == null) collapsed else link.value + collapsed.removePrefix(link.key)
}

/** `/repo/a/../b/./c` → `/repo/b/c`. */
private fun collapsePath(path: String): String {
    val segments = ArrayDeque<String>()
    path.split('/').forEach { segment ->
        when (segment) {
            "", "." -> Unit
            ".." -> segments.removeLastOrNull()
            else -> segments.addLast(segment)
        }
    }
    return "/" + segments.joinToString("/")
}

internal fun projectPaths(
    links: Map<String, String> = emptyMap(),
    writeExtensions: Set<String> = emptySet(),
): ProjectPaths = ProjectPaths(
    root = ROOT,
    writeExtensions = writeExtensions,
    canonicalize = fakeCanonicalizer(links),
)

internal fun projectListing(
    files: Map<String, String> = emptyMap(),
    sizes: Map<String, Long> = emptyMap(),
): ProjectListing = ProjectListing(
    paths = projectPaths(),
    io = FileIoFake(root = ROOT, files = files, sizes = sizes),
)

internal fun projectReader(
    files: Map<String, String> = emptyMap(),
    sizes: Map<String, Long> = emptyMap(),
): ProjectReader = ProjectReader(
    paths = projectPaths(),
    io = FileIoFake(root = ROOT, files = files, sizes = sizes),
)

internal fun projectSearch(
    files: Map<String, String> = emptyMap(),
    sizes: Map<String, Long> = emptyMap(),
): ProjectSearch = ProjectSearch(
    paths = projectPaths(),
    io = FileIoFake(root = ROOT, files = files, sizes = sizes),
)

/**
 * The dispatch layer over one tree. Every tool is wired here, so a test that only cares
 * about the layer's contract — totality, clamping — doesn't have to be rewritten each
 * time a tool joins the server.
 */
internal fun projectFsToolsOver(io: FileIo, writeExtensions: Set<String> = emptySet()): ProjectFsTools {
    val paths = projectPaths(writeExtensions = writeExtensions)
    return ProjectFsTools(
        listing = ProjectListing(paths, io),
        reader = ProjectReader(paths, io),
        search = ProjectSearch(paths, io),
        writer = ProjectWriter(paths, io),
    )
}

internal fun projectFsTools(
    files: Map<String, String> = emptyMap(),
    sizes: Map<String, Long> = emptyMap(),
): ProjectFsTools = projectFsToolsOver(FileIoFake(root = ROOT, files = files, sizes = sizes))

/**
 * A [ProjectWriter] paired with the [FileIoFake] behind it, so a write test can assert on
 * what actually landed on "disk" rather than only on the diff that came back.
 */
internal data class WritableProject(val writer: ProjectWriter, val io: FileIoFake)

internal fun writableProject(
    files: Map<String, String> = emptyMap(),
    writeExtensions: Set<String> = emptySet(),
): WritableProject {
    val io = FileIoFake(root = ROOT, files = files)
    return WritableProject(
        writer = ProjectWriter(paths = projectPaths(writeExtensions = writeExtensions), io = io),
        io = io,
    )
}

/**
 * JSON arguments as the MCP transport would deliver them. A null value is *omitted*
 * rather than sent as JSON null, which is how an absent optional argument really arrives.
 */
internal fun args(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    pairs.forEach { (name, value) ->
        when (value) {
            null -> Unit
            is Int -> put(name, value)
            is Boolean -> put(name, value)
            else -> put(name, value.toString())
        }
    }
}
