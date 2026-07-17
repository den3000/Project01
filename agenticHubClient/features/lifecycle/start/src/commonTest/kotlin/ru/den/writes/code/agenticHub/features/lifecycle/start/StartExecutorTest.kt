package ru.den.writes.code.agenticHub.features.lifecycle.start

import ru.den.writes.code.agenticHub.platform.filesystem.LocalFileSystem
import ru.den.writes.code.agenticHub.platform.filesystem.di.fileSystemModule
import ru.den.writes.code.agenticHub.platform.filesystem.di.fileSystemTestModule
import ru.den.writes.code.agenticHub.platform.database.AppDatabase
import ru.den.writes.code.agenticHub.platform.database.di.databaseTestModule
import org.koin.dsl.koinApplication
import kotlinx.coroutines.test.runTest
import ru.den.writes.code.agenticHub.platform.database.TestDb
import ru.den.writes.code.agenticHub.features.lifecycle.start.StartExecutor
import ru.den.writes.code.agenticHub.features.lifecycle.command.StartCommand
import ru.den.writes.code.agenticHub.features.llm.ModelProvider
import ru.den.writes.code.agenticHub.features.llm.gemini.GeminiModel
import ru.den.writes.code.agenticHub.features.rag.RagIndexer
import ru.den.writes.code.agenticHub.features.rag.embedding.Embedder
import ru.den.writes.code.agenticHub.features.rag.embedding.EmbedderKind
import ru.den.writes.code.agenticHub.features.rag.embedding.EmbedderSelector
import ru.den.writes.code.agenticHub.features.rag.di.ragTestModule
import ru.den.writes.code.agenticHub.features.rag.indexing.IndexStore
import ru.den.writes.code.agenticHub.testutils.IgnoreIos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The [StartExecutor.execute] contract: admin commands run here and yield null; a
 * session command is returned unrun for `main` to launch. This pins the split
 * between "the executor finishes it" and "the executor hands it back".
 */
// @IgnoreIos: opens a real DB via TestDb and touches the eager fileSystemModule
// val — both TODO() on iOS.
@IgnoreIos
class StartExecutorTest {

    @Test
    fun `when a session command - then it is returned unrun`() = runTest {
        TestDb().use { harness ->
            val koin = koinApplication { modules(databaseTestModule(harness.db)) }.koin
            // given
            val oneShot = StartCommand.RunOneShot(
                prompt = "hi",
                maxTokens = null,
                stopSequences = null,
                endSequence = null,
                temperature = null,
                modelProvider = ModelProvider.Gemini(model = GeminiModel.Default, apiKey = "test-key"),
            )

            // when
            val result = StartExecutor(koin.get<AppDatabase>(), koinApplication { modules(fileSystemModule) }.koin.get<LocalFileSystem>()).execute(oneShot)

            // then — same object back, nothing launched
            assertSame(oneShot, result)
        }
    }

    @Test
    fun `when an admin command - then it runs and returns null`() = runTest {
        TestDb().use { harness ->
            val koin = koinApplication { modules(databaseTestModule(harness.db)) }.koin
            // when — list runs against the (empty) test db
            val result = StartExecutor(koin.get<AppDatabase>(), koinApplication { modules(fileSystemModule) }.koin.get<LocalFileSystem>()).execute(StartCommand.ListSessions)

            // then
            assertNull(result)
        }
    }

    @Test
    fun `when rag add targets a directory - then every markdown file lands in one index`() = runTest {
        TestDb().use { harness ->
            // given — one fake fs shared by the source docs, the executor and the index store
            val koin = koinApplication {
                modules(databaseTestModule(harness.db), fileSystemTestModule, ragTestModule)
            }.koin
            val fs = koin.get<LocalFileSystem>()
            val store = IndexStore(fs)
            val embedder = koin.get<Embedder>()
            val executor = StartExecutor(
                koin.get<AppDatabase>(),
                fs,
                RagIndexer(store),
                EmbedderSelector { embedder },
            )
            fs.writeText("/docs/README.md", "# Overview\nthe project readme")
            fs.writeText("/docs/PLANS/plan.md", "# Plan\nthe roadmap")
            fs.writeText("/docs/Main.kt", "// not markdown")

            // when
            val result = executor.execute(
                StartCommand.RagAdd(name = "proj", sourcePath = "/docs", embedder = EmbedderKind.OLLAMA),
            )

            // then — admin command yields null, both .md files (not the .kt) are indexed
            assertNull(result)
            val index = store.load("$RAG_ROOT/proj.json")!!
            assertEquals(
                setOf("README.md", "PLANS/plan.md"),
                index.chunks.map { it.chunk.metadata.source }.toSet(),
            )
        }
    }
}
