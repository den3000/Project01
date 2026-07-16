package ru.den.writes.code.agenticHub.features.rag

import ru.den.writes.code.agenticHub.features.rag.chunking.SourceDocument
import ru.den.writes.code.agenticHub.platform.filesystem.LocalFileSystem

/**
 * Path segments never worth indexing (build output, VCS, IDE / tool state). A file
 * whose relative path crosses any of these is skipped.
 */
private val NOISE_SEGMENTS = setOf("build", ".git", ".gradle", ".idea", ".claude", "node_modules")

/**
 * Walk [root] and build one [SourceDocument] per markdown (`.md`) file, skipping
 * anything under [NOISE_SEGMENTS]. `source` is the file's path relative to [root]
 * (its stable citation locator), `title` is the file name, `text` is the body.
 * Files that vanish between walk and read are dropped; result is empty when [root]
 * holds no indexable markdown. The thin glue that lets `-rag add <name> src <dir>`
 * feed a whole docs tree into the indexing pipeline.
 */
public fun markdownCorpus(fs: LocalFileSystem, root: String): List<SourceDocument> =
    fs.walkFiles(root)
        .filter { it.endsWith(".md", ignoreCase = true) }
        .filterNot { rel -> rel.split('/').any { it in NOISE_SEGMENTS } }
        .sorted()
        .mapNotNull { rel ->
            fs.readText("$root/$rel")?.let { text ->
                SourceDocument(source = rel, title = rel.substringAfterLast('/'), text = text)
            }
        }
