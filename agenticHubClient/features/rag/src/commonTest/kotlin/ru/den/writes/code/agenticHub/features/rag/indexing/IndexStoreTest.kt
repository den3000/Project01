package ru.den.writes.code.agenticHub.features.rag.indexing

import org.koin.dsl.koinApplication
import ru.den.writes.code.agenticHub.features.rag.KB_FILE
import ru.den.writes.code.agenticHub.features.rag.SAMPLE_INDEX_PATH
import ru.den.writes.code.agenticHub.features.rag.SAMPLE_SECTION
import ru.den.writes.code.agenticHub.features.rag.sampleIndex
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
        store.save(index, SAMPLE_INDEX_PATH)
        val actual = store.load(SAMPLE_INDEX_PATH)

        // then
        assertEquals(index, actual)
    }

    @Test
    fun `when loading absent path - then null`() {
        // given
        val fs = koin.get<LocalFileSystem>()
        val store = IndexStore(fs)

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
        store.save(sampleIndex(), SAMPLE_INDEX_PATH)

        // when
        val actual = store.load(SAMPLE_INDEX_PATH)!!.chunks.single().chunk.metadata

        // then
        assertEquals(KB_FILE, actual.source)
        assertEquals(KB_FILE, actual.title)
        assertEquals(SAMPLE_SECTION, actual.section)
        assertEquals(0, actual.chunkId)
    }
}
