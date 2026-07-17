package ru.den.writes.code.agenticHub.platform.filesystem

/**
 * In-memory [LocalFileSystem] for tests: a path→content map with a separate set
 * of directories. `internal` — only reachable via
 * [fileSystemTestModule][ru.den.writes.code.agenticHub.platform.filesystem.di.fileSystemTestModule],
 * under the [LocalFileSystem] interface. Paths are `/`-separated strings (as the
 * file memory store builds them); [listFileNames] returns regular files directly
 * in a directory. Not thread-safe (tests are single-threaded).
 */
internal class LocalFileSystemFake : LocalFileSystem {
    private val files = mutableMapOf<String, String>()
    private val dirs = mutableSetOf<String>()

    override fun mkdirs(path: String) {
        dirs += path
    }

    override fun exists(path: String): Boolean = path in files || path in dirs

    override fun readText(path: String): String? = files[path]

    override fun writeText(path: String, text: String) {
        files[path] = text
    }

    override fun delete(path: String): Boolean = files.remove(path) != null || dirs.remove(path)

    override fun listFileNames(dir: String): List<String> {
        val prefix = "$dir/"
        return files.keys
            .filter { it.startsWith(prefix) && '/' !in it.removePrefix(prefix) }
            .map { it.removePrefix(prefix) }
    }

    override fun isDirectory(path: String): Boolean =
        path in dirs || files.keys.any { it.startsWith("$path/") }

    override fun walkFiles(dir: String): List<String> {
        val prefix = "$dir/"
        return files.keys
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
    }
}
