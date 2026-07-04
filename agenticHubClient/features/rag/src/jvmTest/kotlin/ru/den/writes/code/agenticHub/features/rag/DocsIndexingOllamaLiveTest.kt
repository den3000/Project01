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
import ru.den.writes.code.agenticHub.features.rag.indexing.VectorIndex
import ru.den.writes.code.agenticHub.platform.filesystem.LocalFileSystem
import ru.den.writes.code.agenticHub.platform.filesystem.di.fileSystemModule
import ru.den.writes.code.agenticHub.platform.network.di.networkModule
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Indexing a real corpus (the repo's markdown docs) with the real Ollama model. One
// test compares the chunking strategies; one per strategy runs the full pipeline —
// chunk → embed → save to JSON → reload from JSON → query the reloaded index. Opt-in
// live test (gated as *LiveTest; see LIVE_TESTS.md) — run with -PliveTests. Resolves
// everything from the real production graph.
class DocsIndexingOllamaLiveTest {

    private val koin = koinApplication {
        modules(ragModule, networkModule, fileSystemModule)
    }.koin

    @Test
    fun `when the corpus is chunked by each strategy - then the strategies produce different chunkings`() {
        // given
        val docs = loadMarkdownCorpus(repoRoot())
        val totalChars = docs.sumOf { it.text.length }
        val strategies = mapOf(
            "fixed(1000,ov100)" to FixedSizeChunking(chunkSize = 1000, overlap = 100),
            "token(400,ov40)" to TokenChunking(tokensPerChunk = 400, overlap = 40),
            "structural" to StructuralChunking(),
        )

        // when
        val sizesByStrategy = strategies.mapValues { (_, strategy) ->
            docs.flatMap { strategy.chunk(it) }.map { it.text.length }
        }

        // then
        assertTrue(totalChars >= 60_000, "corpus too small ($totalChars chars) — need a substantial multi-doc corpus")
        assertEquals(
            strategies.size,
            sizesByStrategy.values.map { it.size }.toSet().size,
            "strategies should chunk the corpus differently",
        )
        println("[chunking comparison] over ${docs.size} docs, $totalChars chars:")
        sizesByStrategy.forEach { (name, sizes) ->
            println(
                "  %-20s %4d chunks  chars avg=%-5d min=%-4d max=%d"
                    .format(name, sizes.size, sizes.average().toInt(), sizes.min(), sizes.max()),
            )
        }
    }

    @Test
    fun `when docs are indexed with fixed-size chunking - then the reloaded index answers a query`() = liveOllamaTest(koin) {
        // given
        val root = repoRoot()
        val docs = loadMarkdownCorpus(root)
        val strategy = FixedSizeChunking(chunkSize = 1000, overlap = 100)
        val indexPath = "${indexDir(root)}/docs-index-fixed.json"

        // when
        val pipeline = koin.get<IndexingPipeline> { parametersOf(strategy) }
        val index = pipeline.index(docs)
        val indexStore = koin.get<IndexStore>()
        indexStore.save(index, indexPath)
        val loadedIndex = requireNotNull(indexStore.load(indexPath)) { "index did not reload from $indexPath" }
        val retriever = koin.get<Retriever> { parametersOf(loadedIndex) }
        val hits = retriever.retrieve(DI_QUERY, topK = 5)

        // then
        assertEquals(index, loadedIndex)
        assertChunksCarryMetadataAndEmbeddings(loadedIndex)
        assertTrue(hits.isNotEmpty())
        println("[fixed] ${loadedIndex.chunks.size} chunks; top: ${hits.first().chunk.metadata.title} / ${hits.first().chunk.metadata.section}")
    }

    @Test
    fun `when docs are indexed with token chunking - then the reloaded index answers a query`() = liveOllamaTest(koin) {
        // given
        val root = repoRoot()
        val docs = loadMarkdownCorpus(root)
        val strategy = TokenChunking(tokensPerChunk = 400, overlap = 40)
        val indexPath = "${indexDir(root)}/docs-index-token.json"

        // when
        val pipeline = koin.get<IndexingPipeline> { parametersOf(strategy) }
        val index = pipeline.index(docs)
        val indexStore = koin.get<IndexStore>()
        indexStore.save(index, indexPath)
        val loadedIndex = requireNotNull(indexStore.load(indexPath)) { "index did not reload from $indexPath" }
        val retriever = koin.get<Retriever> { parametersOf(loadedIndex) }
        val hits = retriever.retrieve(DI_QUERY, topK = 5)

        // then
        assertEquals(index, loadedIndex)
        assertChunksCarryMetadataAndEmbeddings(loadedIndex)
        assertTrue(hits.isNotEmpty())
        println("[token] ${loadedIndex.chunks.size} chunks; top: ${hits.first().chunk.metadata.title} / ${hits.first().chunk.metadata.section}")
    }

    @Test
    fun `when docs are indexed with structural chunking - then the reloaded index answers a query`() = liveOllamaTest(koin) {
        // given
        val root = repoRoot()
        val docs = loadMarkdownCorpus(root)
        val strategy = StructuralChunking()
        val indexPath = "${indexDir(root)}/docs-index-structural.json"

        // when
        val pipeline = koin.get<IndexingPipeline> { parametersOf(strategy) }
        val index = pipeline.index(docs)
        val indexStore = koin.get<IndexStore>()
        indexStore.save(index, indexPath)
        val loadedIndex = requireNotNull(indexStore.load(indexPath)) { "index did not reload from $indexPath" }
        val retriever = koin.get<Retriever> { parametersOf(loadedIndex) }
        val hits = retriever.retrieve(DI_QUERY, topK = 5)

        // then
        assertEquals(index, loadedIndex)
        assertChunksCarryMetadataAndEmbeddings(loadedIndex)
        assertTrue(hits.isNotEmpty())
        println("[structural] ${loadedIndex.chunks.size} chunks; top: ${hits.first().chunk.metadata.title} / ${hits.first().chunk.metadata.section}")
    }

    private fun assertChunksCarryMetadataAndEmbeddings(index: VectorIndex) {
        assertTrue(index.chunks.isNotEmpty())
        index.chunks.forEach {
            assertTrue(it.embedding.isNotEmpty(), "empty embedding for ${it.chunk.metadata.source}")
            assertTrue(it.chunk.metadata.source.isNotEmpty(), "missing source")
            assertTrue(it.chunk.metadata.title.isNotEmpty(), "missing title")
        }
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

    private fun indexDir(root: File): String {
        val dir = "${root.path}/agenticHubClient/features/rag/build/rag-index"
        koin.get<LocalFileSystem>().mkdirs(dir)
        return dir
    }

    private fun repoRoot(): File {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile ?: error("repo root (settings.gradle.kts) not found from ${File("").absolutePath}")
        }
        return dir
    }

    private companion object {
        const val DI_QUERY = "how does dependency injection with Koin work in this project"
    }
}
