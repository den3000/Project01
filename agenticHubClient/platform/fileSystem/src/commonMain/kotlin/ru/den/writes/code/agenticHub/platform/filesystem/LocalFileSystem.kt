package ru.den.writes.code.agenticHub.platform.filesystem

/**
 * Minimal local-filesystem port — enough for the markdown-backed memory store
 * (profiles / rules / tasks). Path-string based so the neutral (KMP) callers
 * never touch a platform file type; the actual I/O lives in the per-target
 * implementations obtained via [localFileSystem].
 */
public interface LocalFileSystem {
    /** Create [path] and any missing parents. No-op if it already exists. */
    public fun mkdirs(path: String)

    /** `true` if a file (or directory) exists at [path]. */
    public fun exists(path: String): Boolean

    /** UTF-8 contents of the file at [path], or `null` if it doesn't exist. */
    public fun readText(path: String): String?

    /** Overwrite the file at [path] with [text] (UTF-8), creating it if needed. */
    public fun writeText(path: String, text: String)

    /** Delete the file at [path]; returns `true` iff a file was removed. */
    public fun delete(path: String): Boolean

    /** Names of the regular files directly in [dir] (empty if [dir] is absent). */
    public fun listFileNames(dir: String): List<String>
}

/** The default [LocalFileSystem] for the current platform. */
public expect fun localFileSystem(): LocalFileSystem
