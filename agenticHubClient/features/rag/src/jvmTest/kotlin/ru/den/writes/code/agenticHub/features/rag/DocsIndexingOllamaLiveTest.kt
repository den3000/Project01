package ru.den.writes.code.agenticHub.features.rag

import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import ru.den.writes.code.agenticHub.features.rag.chunking.FixedSizeChunking
import ru.den.writes.code.agenticHub.features.rag.chunking.SourceDocument
import ru.den.writes.code.agenticHub.features.rag.chunking.StructuralChunking
import ru.den.writes.code.agenticHub.features.rag.chunking.TokenChunking
import ru.den.writes.code.agenticHub.features.rag.di.ragModule
import ru.den.writes.code.agenticHub.features.rag.indexing.IndexStore
import ru.den.writes.code.agenticHub.features.rag.indexing.IndexingPipeline
import ru.den.writes.code.agenticHub.platform.filesystem.LocalFileSystem
import ru.den.writes.code.agenticHub.platform.filesystem.di.fileSystemModule
import ru.den.writes.code.agenticHub.platform.network.di.networkModule
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The Day-21 deliverable end-to-end on a real corpus: read the repo's markdown docs
// (~30 pages), compare 3 chunking strategies, embed with the real Ollama model, save
// a JSON index with metadata, and query it. Opt-in live test (gated as *LiveTest; see
// LIVE_TESTS.md) — run with -PliveTests. Resolves everything from the real production graph.
class DocsIndexingOllamaLiveTest {

    private val koin = koinApplication {
        modules(ragModule, networkModule, fileSystemModule)
    }.koin

    @Test
    fun `when the project docs are indexed with the real model - then a JSON index with metadata is built and queryable`() = liveOllamaTest(koin) {
        // given — real corpus: every markdown doc in the repo (README / CLAUDE.md / DI.md / per-module READMEs)
        val root = repoRoot()
        val docs = loadMarkdownCorpus(root)
        val totalChars = docs.sumOf { it.text.length }
        println("[corpus] ${docs.size} markdown docs, $totalChars chars (~${totalChars / 3000} pages)")
        assertTrue(totalChars >= 60_000, "corpus too small ($totalChars chars) — expected 20-30+ pages")

        // усиление: compare chunking strategies over the whole corpus
        val strategies = mapOf(
            "fixed(1000,ov100)" to FixedSizeChunking(chunkSize = 1000, overlap = 100),
            "token(400,ov40)" to TokenChunking(tokensPerChunk = 400, overlap = 40),
            "structural" to StructuralChunking(),
        )
        println("[chunking comparison]")
        strategies.forEach { (name, strategy) ->
            val sizes = docs.flatMap { strategy.chunk(it) }.map { it.text.length }
            println(
                "  %-20s %4d chunks  chars avg=%-5d min=%-4d max=%d"
                    .format(name, sizes.size, sizes.average().toInt(), sizes.min(), sizes.max()),
            )
        }

        // build + persist the index with the REAL embedder (structural chunking)
        val index = koin.get<IndexingPipeline> { parametersOf(StructuralChunking()) }.index(docs)
        assertTrue(index.chunks.isNotEmpty())

        val fs = koin.get<LocalFileSystem>()
        val indexDir = "${root.path}/agenticHubClient/features/rag/build/rag-index"
        val indexPath = "$indexDir/docs-index.json"
        fs.mkdirs(indexDir)
        koin.get<IndexStore>().save(index, indexPath)
        println("[index] ${index.chunks.size} chunks, ${index.chunks.first().embedding.size}-dim vectors → $indexPath")

        // every chunk carries provenance metadata + a real embedding
        index.chunks.forEach {
            assertTrue(it.embedding.isNotEmpty(), "empty embedding for ${it.chunk.metadata.source}")
            assertTrue(it.chunk.metadata.source.isNotEmpty(), "missing source")
            assertTrue(it.chunk.metadata.title.isNotEmpty(), "missing title")
        }
        // persistence round-trips
        assertEquals(index, koin.get<IndexStore>().load(indexPath))

        // query the real index
        val hits = koin.get<Retriever> { parametersOf(index) }
            .retrieve("how does dependency injection with Koin work in this project", topK = 5)
        println("[query] top ${hits.size} for the DI question:")
        hits.forEach { println("  %.3f  %s / %s".format(it.score, it.chunk.metadata.title, it.chunk.metadata.section)) }
        assertTrue(hits.isNotEmpty())
    }

    private fun loadMarkdownCorpus(root: File): List<SourceDocument> {
        val excluded = setOf("build", ".git", ".gradle", ".claude", ".idea")
        return root.walkTopDown()
            .onEnter { it.name !in excluded }
            .filter { it.isFile && it.extension == "md" }
            .map { file ->
                SourceDocument(
                    source = file.relativeTo(root).path,
                    title = file.name,
                    text = file.readText(),
                )
            }
            .toList()
    }

    private fun repoRoot(): File {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile ?: error("repo root (settings.gradle.kts) not found from ${File("").absolutePath}")
        }
        return dir
    }
}
