package ru.den.writes.code.agenticHub.features.rag

import org.koin.dsl.koinApplication
import ru.den.writes.code.agenticHub.platform.filesystem.LocalFileSystem
import ru.den.writes.code.agenticHub.platform.filesystem.di.fileSystemTestModule
import ru.den.writes.code.agenticHub.testutils.IgnoreIos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// @IgnoreIos: fileSystemTestModule shares a file with the eager, iOS-TODO
// fileSystemModule val, which Kotlin/Native initializes on first touch.
@IgnoreIos
class IndexStoreTest {

    // Один koin на класс; fileSystemTestModule на factory → каждый get() свежий фейк.
    private val koin = koinApplication { modules(fileSystemTestModule) }.koin

    @Test
    fun `when saved and loaded - then index round-trips`() {
        // given
        val fs = koin.get<LocalFileSystem>()
        val store = IndexStore(fs)
        val index = sampleIndex()

        // when
        store.save(index, PATH)
        val actual = store.load(PATH)

        // then
        assertEquals(index, actual)
    }

    @Test
    fun `when loading absent path - then null`() {
        // given
        val store = IndexStore(koin.get<LocalFileSystem>())

        // when
        val actual = store.load("nowhere/missing.json")

        // then
        assertNull(actual)
    }

    @Test
    fun `when round-tripped - then chunk metadata survives`() {
        // given
        val fs = koin.get<LocalFileSystem>()
        val store = IndexStore(fs)
        store.save(sampleIndex(), PATH)

        // when
        val actual = store.load(PATH)!!.chunks.single().chunk.metadata

        // then
        assertEquals("kb.md", actual.source)
        assertEquals("kb.md", actual.title)
        assertEquals("Intro", actual.section)
        assertEquals(0, actual.chunkId)
    }

    private fun sampleIndex(): VectorIndex = VectorIndex(
        listOf(
            IndexedChunk(
                chunk = Chunk(
                    text = "hello world",
                    metadata = ChunkMetadata(source = "kb.md", title = "kb.md", section = "Intro", chunkId = 0),
                ),
                embedding = listOf(0.1f, 0.2f, 0.3f),
            ),
        ),
    )

    private companion object {
        const val PATH = "indexes/kb.json"
    }
}
