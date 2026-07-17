package ru.den.writes.code.agenticHub.features.rag

import ru.den.writes.code.agenticHub.features.rag.chunking.SourceDocument
import ru.den.writes.code.agenticHub.platform.filesystem.LocalFileSystem

/**
 * Path segments never worth indexing (build output, VCS, IDE / tool state). A file
 * whose relative path crosses any of these is skipped.
 */
private val NOISE_SEGMENTS = setOf("build", ".git", ".gradle", ".idea", ".claude", "node_modules")

/** What a project corpus indexes by default: documentation plus Kotlin sources. */
public val SOURCE_EXTENSIONS: Set<String> = setOf("md", "kt", "kts")

/**
 * Walk [root] and build one [SourceDocument] per file whose extension is in
 * [extensions], skipping anything under [NOISE_SEGMENTS]. `source` is the file's path
 * relative to [root] (its stable citation locator), `title` is the file name, `text` is
 * the body. Files that vanish between walk and read are dropped; the result is empty
 * when [root] holds nothing indexable.
 *
 * The thin glue that lets `-rag add <name> src <dir>` feed a whole project — docs *and*
 * code — into the indexing pipeline. Pair it with
 * [ByExtensionChunking][ru.den.writes.code.agenticHub.features.rag.chunking.ByExtensionChunking]
 * so each format is cut its own way; one corpus, one index.
 */
public fun sourceCorpus(
    fs: LocalFileSystem,
    root: String,
    extensions: Set<String> = SOURCE_EXTENSIONS,
): List<SourceDocument> =
    fs.walkFiles(root)
        .filter { rel -> rel.substringAfterLast('/').substringAfterLast('.', "").lowercase() in extensions }
        .filterNot { rel -> rel.split('/').any { it in NOISE_SEGMENTS } }
        .sorted()
        .mapNotNull { rel ->
            fs.readText("$root/$rel")?.let { text ->
                SourceDocument(source = rel, title = rel.substringAfterLast('/'), text = text)
            }
        }
