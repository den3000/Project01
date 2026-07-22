package ru.den.writes.code.agenticHub.mcps.projectfs

import java.io.File

/** How deep the walk descends — a guard against symlink loops, not a real project shape. */
internal const val WALK_MAX_DEPTH = 12

/** How many leading bytes are sniffed for a NUL before a file is called binary. */
internal const val BINARY_SNIFF_BYTES = 8_000

/** Past this size a file is flagged rather than counted — reading it would blow the context. */
internal const val LARGE_FILE_BYTES = 1_000_000L

private const val STAT_BUFFER_BYTES = 16 * 1024

private const val ZERO_BYTE: Byte = 0

private const val NEWLINE_BYTE: Byte = 0x0A

/**
 * What one pass over a file yields: how big it is and how many lines it has.
 *
 * The two travel together because a listing wants both for every path it shows, and
 * asking separately is what turned a single listing into three hundred full reads.
 * [lines] is null when the file was too large to count — past [LARGE_FILE_BYTES] the
 * count isn't worth the read, and no tool will open the file anyway.
 */
data class FileStat(val bytes: Long, val lines: Int?)

/**
 * The I/O edge of the server, kept behind an interface so every tool body — all the
 * formatting, filtering and limits — is exercised offline on an in-memory fake.
 *
 * Every `absolute` argument has already cleared [ProjectPaths]; implementations do no
 * validation of their own. The port sits *after* the gate on purpose: a fake that took
 * raw model input would quietly bypass the very checks the tests are meant to prove.
 */
interface FileIo {
    /**
     * Every file under the root, as paths relative to it.
     *
     * Noise directories are not descended — an implementation that returned them would be
     * lying about the tree the tools see, and the filters downstream would grow a branch
     * nothing can reach.
     */
    fun walk(): List<String>

    /**
     * Size and line count from one read; null when absent.
     *
     * Only a listing needs this. Everything else guards with [size] — a stat syscall —
     * and then reads once, so no operation walks the same bytes twice.
     */
    fun stat(absolute: String): FileStat?

    /** Size in bytes without reading the file; 0 when absent. */
    fun size(absolute: String): Long

    /** File contents as UTF-8, or null when it vanished between walk and read. */
    fun read(absolute: String): String?

    /** Whether the file is already there — tells "created" from "updated". */
    fun exists(absolute: String): Boolean

    /** Write [text] as UTF-8, creating parent directories as needed. */
    fun write(absolute: String, text: String)
}

/**
 * Production [FileIo] over a real directory tree. The walk skips [NOISE_SEGMENTS] on the
 * way down (cheaper than filtering a `build/` tree afterwards) and is depth-capped so a
 * symlink loop can't hang the server — there is no timeout anywhere on the MCP path.
 */
class RealFileIo(private val root: String) : FileIo {
    private val rootFile = File(root)

    override fun walk(): List<String> =
        rootFile.walkTopDown()
            .maxDepth(WALK_MAX_DEPTH)
            .onEnter { dir -> dir.name !in NOISE_SEGMENTS }
            .filter { it.isFile }
            .map { it.toRelativeString(rootFile) }
            .toList()

    /**
     * Streams the file once, counting newlines as it goes, so the bytes are never
     * materialised as a String. A file past [LARGE_FILE_BYTES] is described from its
     * length alone — nothing will read it, so counting its lines would be a full read
     * spent on a number no one uses.
     */
    override fun stat(absolute: String): FileStat? {
        val file = File(absolute).takeIf { it.isFile } ?: return null
        val length = file.length()
        if (length > LARGE_FILE_BYTES) return FileStat(bytes = length, lines = null)

        return runCatching {
            var newlines = 0
            var seen = 0L
            var lastByte: Byte = 0
            file.inputStream().buffered().use { stream ->
                val buffer = ByteArray(STAT_BUFFER_BYTES)
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    for (index in 0 until read) {
                        if (buffer[index] == NEWLINE_BYTE) newlines++
                    }
                    seen += read
                    lastByte = buffer[read - 1]
                }
            }
            val lines = when {
                seen == 0L -> 0
                lastByte == NEWLINE_BYTE -> newlines
                else -> newlines + 1
            }
            FileStat(bytes = seen, lines = lines)
        }.getOrNull()
    }

    override fun size(absolute: String): Long = File(absolute).length()

    override fun read(absolute: String): String? =
        File(absolute).takeIf { it.isFile }?.runCatching { readText(Charsets.UTF_8) }?.getOrNull()

    override fun exists(absolute: String): Boolean = File(absolute).isFile

    override fun write(absolute: String, text: String) {
        val file = File(absolute)
        file.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
    }
}
