package ru.den.writes.code.agenticHub.features.rag

import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import ru.den.writes.code.agenticHub.features.rag.chunking.StructuralChunking
import ru.den.writes.code.agenticHub.features.rag.embedding.EmbedderFake
import ru.den.writes.code.agenticHub.features.rag.indexing.IndexStore
import ru.den.writes.code.agenticHub.platform.filesystem.LocalFileSystem
import ru.den.writes.code.agenticHub.platform.filesystem.di.fileSystemTestModule
import ru.den.writes.code.agenticHub.testutils.IgnoreIos
import kotlin.test.Test
import kotlin.test.assertEquals

// @IgnoreIos: fileSystemTestModule shares a file with the eager, iOS-TODO
// fileSystemModule val, which Kotlin/Native initializes on first touch.
@IgnoreIos
class RagIndexerTest {

    private val koin = koinApplication { modules(fileSystemTestModule) }.koin
    private val path = "indexes/corpus.json"

    @Test
    fun `when indexing a corpus - then chunk count sums every document`() = runTest {
        // given
        val fs = koin.get<LocalFileSystem>()
        val store = IndexStore(fs)
        val indexer = RagIndexer(store)
        val docs = listOf(
            doc(source = "a.md", title = "a.md", text = "# One\nalpha body"),
            doc(source = "b.md", title = "b.md", text = "# Two\nbeta body\n\n# Three\ngamma body"),
        )

        // when
        val count = indexer.index(docs, path, StructuralChunking(), EmbedderFake())

        // then
        assertEquals(3, count)
    }

    @Test
    fun `when indexing a corpus - then every document's source is retrievable from disk`() = runTest {
        // given
        val fs = koin.get<LocalFileSystem>()
        val store = IndexStore(fs)
        val indexer = RagIndexer(store)
        val docs = listOf(
            doc(source = "a.md", title = "a.md", text = "# One\nalpha body"),
            doc(source = "b.md", title = "b.md", text = "# Two\nbeta body"),
        )

        // when
        indexer.index(docs, path, StructuralChunking(), EmbedderFake())
        val loaded = store.load(path)!!

        // then
        assertEquals(setOf("a.md", "b.md"), loaded.chunks.map { it.chunk.metadata.source }.toSet())
    }
}
