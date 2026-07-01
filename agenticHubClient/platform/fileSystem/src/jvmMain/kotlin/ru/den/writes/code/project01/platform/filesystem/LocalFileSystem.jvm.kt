package ru.den.writes.code.project01.platform.filesystem

import java.io.File

/** `java.io.File`-backed [LocalFileSystem] for the JVM. */
internal class JvmLocalFileSystem : LocalFileSystem {
    override fun mkdirs(path: String) {
        File(path).mkdirs()
    }

    override fun exists(path: String): Boolean = File(path).exists()

    override fun readText(path: String): String? =
        File(path).takeIf { it.exists() }?.readText(Charsets.UTF_8)

    override fun writeText(path: String, text: String) {
        File(path).writeText(text, Charsets.UTF_8)
    }

    override fun delete(path: String): Boolean = File(path).delete()

    override fun listFileNames(dir: String): List<String> =
        File(dir).listFiles()?.filter { it.isFile }?.map { it.name } ?: emptyList()
}

public actual fun localFileSystem(): LocalFileSystem = JvmLocalFileSystem()
