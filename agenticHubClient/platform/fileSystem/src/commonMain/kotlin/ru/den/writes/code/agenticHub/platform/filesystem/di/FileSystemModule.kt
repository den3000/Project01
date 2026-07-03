package ru.den.writes.code.agenticHub.platform.filesystem.di

import org.koin.core.module.Module
import org.koin.dsl.module
import ru.den.writes.code.agenticHub.platform.filesystem.LocalFileSystem

/**
 * Koin module that binds the platform's [LocalFileSystem].
 *
 * Expressed per target via `actual`: the binding itself can take whatever the
 * platform needs to build an instance (e.g. an Android `Context`) without that
 * leaking into a shared factory signature — the reason this replaces the old
 * `expect fun localFileSystem()`.
 */
internal expect fun fileSystemModule(): Module

/** The platform's file-system Koin module. */
public val fileSystemModule: Module = fileSystemModule()

/**
 * Test counterpart of [fileSystemModule]: binds [LocalFileSystem] to an
 * in-memory fake. A plain `common` module (the fake is platform-agnostic), so
 * — unlike [fileSystemModule], whose android/ios `actual` is still `TODO` — it
 * resolves on **every** target, letting integration graphs run their tests in
 * any platform environment. Compose it in place of [fileSystemModule]; seed and
 * assert through the public [LocalFileSystem] surface. See agenticHubClient/DI.md.
 */
public val fileSystemTestModule: Module = module {
    single<LocalFileSystem> { InMemoryLocalFileSystem() }
}

/**
 * In-memory [LocalFileSystem] for tests: a path→content map with a separate set
 * of directories. `internal` — only reachable via [fileSystemTestModule], under
 * the [LocalFileSystem] interface. Paths are `/`-separated strings (as the file
 * memory store builds them); [listFileNames] returns regular files directly in a
 * directory. Not thread-safe (tests are single-threaded).
 */
internal class InMemoryLocalFileSystem : LocalFileSystem {
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
}
