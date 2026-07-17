package ru.den.writes.code.agenticHub.features.lifecycle.start

import ru.den.writes.code.agenticHub.features.lifecycle.command.StartCommand
import ru.den.writes.code.agenticHub.features.memory.isValidProfileName
import ru.den.writes.code.agenticHub.features.rag.RagIndexer
import ru.den.writes.code.agenticHub.features.rag.sourceCorpus
import ru.den.writes.code.agenticHub.features.rag.chunking.ByExtensionChunking
import ru.den.writes.code.agenticHub.features.rag.chunking.ChunkingStrategy
import ru.den.writes.code.agenticHub.features.rag.chunking.SourceDocument
import ru.den.writes.code.agenticHub.features.rag.chunking.StructuralChunking
import ru.den.writes.code.agenticHub.features.rag.chunking.TokenChunking
import ru.den.writes.code.agenticHub.features.rag.embedding.EmbedderSelector
import ru.den.writes.code.agenticHub.platform.database.AppDatabase
import ru.den.writes.code.agenticHub.platform.filesystem.LocalFileSystem
import ru.den.writes.code.agenticHub.platform.filesystem.homeDirectory
import ru.den.writes.code.agenticHub.platform.logging.logErr

/**
 * Root of the on-disk memory layer. Profile, rules and task notes live under
 * this folder as markdown files. Shared by the admin memory ops (via [AdminOps])
 * and the session's `memoryProvider` accessor.
 */
val MEMORY_ROOT: String = "${homeDirectory()}/.project01-cli/memory"

/**
 * Root of the on-disk RAG indices. Each `-rag add <name> …` writes `<name>.json`
 * here; `/rag <name>` in a session loads it back. Sits next to [MEMORY_ROOT].
 */
val RAG_ROOT: String = "${homeDirectory()}/.project01-cli/rag"

/**
 * How `-rag add` cuts a project corpus: markdown by its headings (sections survive as
 * chunk metadata), everything else — Kotlin sources — by token windows, since code
 * carries no `#` headings and would otherwise land in a single chunk per file.
 */
private fun projectChunking(): ChunkingStrategy = ByExtensionChunking(
    default = TokenChunking(tokensPerChunk = 200, overlap = 40),
    byExtension = mapOf("md" to StructuralChunking()),
)

/**
 * Runs a parsed [StartCommand] against the runtime — the "how" to the parser's
 * "what". Admin commands (list / clean / inflate / memory) run through [AdminOps]
 * (features:viewModel) and their notices are printed here on the tagged stream;
 * a [StartCommand.SessionInitialState] is returned unrun for `main` to launch.
 * Owns only the [db].
 */
public class StartExecutor(
    private val db: AppDatabase,
    private val fs: LocalFileSystem,
    private val ragIndexer: RagIndexer? = null,
    private val embedderSelector: EmbedderSelector? = null,
) {
    private val ops = AdminOps(db, fs)

    public suspend fun execute(command: StartCommand): StartCommand.SessionInitialState? = when (command) {
        is StartCommand.ListSessions -> { ops.listSessions().print(); null }
        is StartCommand.CleanHistory -> { ops.cleanHistory().print(); null }
        is StartCommand.CleanSession -> { ops.cleanSession(command.sessionId).print(); null }
        is StartCommand.InflateSession -> { ops.inflateSession(command).print(); null }
        is StartCommand.MemoryOp -> { ops.handleMemoryCommand(command.action, MEMORY_ROOT).print(); null }
        is StartCommand.RagAdd -> { handleRagAdd(command); null }
        is StartCommand.SessionInitialState -> command
    }

    /**
     * Index [RagAdd.sourcePath] into `RAG_ROOT/<name>.json` with [projectChunking] + the
     * graph's embedder (Ollama). Validates the name (same shape as a profile name).
     * A **directory** source is walked for its project corpus (docs *and* Kotlin code);
     * a **file** source is indexed as one document. Reads through the fs port and prints
     * a tagged status line. Missing indexer (no RAG in the graph), a missing path, or an
     * empty corpus yields an error notice, no throw.
     */
    private suspend fun handleRagAdd(command: StartCommand.RagAdd) {
        val indexer = ragIndexer ?: run { logErr("[rag] indexing is unavailable in this build"); return }
        val selector = embedderSelector ?: run { logErr("[rag] indexing is unavailable in this build"); return }
        if (!isValidProfileName(command.name)) {
            logErr("[rag] invalid name '${command.name}' (alphanumeric / '_' / '-', up to 64 chars)")
            return
        }
        fs.mkdirs(RAG_ROOT)
        val path = "$RAG_ROOT/${command.name}.json"
        val embedder = selector.select(command.embedder)
        val backend = command.embedder.name.lowercase()

        if (fs.isDirectory(command.sourcePath)) {
            val docs = sourceCorpus(fs, command.sourcePath)
            if (docs.isEmpty()) {
                logErr("[rag] nothing indexable under '${command.sourcePath}' (.md / .kt / .kts)")
                return
            }
            val chunks = indexer.index(docs, path, projectChunking(), embedder)
            println("[rag] indexed '${command.name}' (${docs.size} doc(s), $chunks chunk(s), $backend) → $path")
            return
        }

        val text = fs.readText(command.sourcePath) ?: run {
            logErr("[rag] no file or directory at '${command.sourcePath}'")
            return
        }
        val name = command.sourcePath.substringAfterLast('/')
        val chunks = indexer.index(SourceDocument(source = name, title = name, text = text), path, projectChunking(), embedder)
        println("[rag] indexed '${command.name}' ($chunks chunk(s), $backend) → $path")
    }
}

/** Print each admin notice on its tagged stream, preserving the CLI's stdout/stderr split. */
private fun List<AdminNotice>.print() = forEach {
    when (it.stream) {
        OutputStream.STDOUT -> println(it.text)
        OutputStream.STDERR -> logErr(it.text)
    }
}
