package ru.den.writes.code.agenticHub.features.rag.chunking

import ru.den.writes.code.agenticHub.features.rag.doc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FixedSizeChunkingTest {

    //region windowing
    @Test
    fun `when body shorter than chunkSize - then single chunk holds whole text`() {
        // given
        val text = "abc"
        val strategy = FixedSizeChunking(chunkSize = 10)

        // when
        val actual = strategy.chunk(doc(text))

        // then
        assertEquals(1, actual.size)
        assertEquals(text, actual.single().text)
    }

    @Test
    fun `when body length equals chunkSize - then single chunk and no empty tail`() {
        // given
        val text = "abcd"
        val strategy = FixedSizeChunking(chunkSize = 4)

        // when
        val actual = strategy.chunk(doc(text))

        // then
        assertEquals(listOf(text), actual.map { it.text })
    }

    @Test
    fun `when no overlap - then windows tile the text end to end`() {
        // given
        val text = "abcdefghij"
        val strategy = FixedSizeChunking(chunkSize = 4, overlap = 0)

        // when
        val actual = strategy.chunk(doc(text))

        // then
        assertEquals(
            listOf(text.substring(0, 4), text.substring(4, 8), text.substring(8, 10)),
            actual.map { it.text },
        )
    }

    @Test
    fun `when length is exact multiple of chunkSize - then no trailing empty chunk`() {
        // given
        val text = "abcdefgh"
        val strategy = FixedSizeChunking(chunkSize = 4, overlap = 0)

        // when
        val actual = strategy.chunk(doc(text))

        // then
        assertEquals(listOf(text.substring(0, 4), text.substring(4, 8)), actual.map { it.text })
    }

    @Test
    fun `when overlap set - then consecutive chunks share overlap chars`() {
        // given
        val text = "abcdefghij"
        val strategy = FixedSizeChunking(chunkSize = 4, overlap = 1)

        // when
        val actual = strategy.chunk(doc(text))

        // then
        assertEquals(
            listOf(text.substring(0, 4), text.substring(3, 7), text.substring(6, 10)),
            actual.map { it.text },
        )
    }

    @Test
    fun `when body blank - then no chunks`() {
        // given
        val text = "   "
        val strategy = FixedSizeChunking(chunkSize = 4)

        // when
        val actual = strategy.chunk(doc(text))

        // then
        assertTrue(actual.isEmpty())
    }
    //endregion

    //region metadata
    @Test
    fun `when chunked - then metadata carries source and title with null section`() {
        // given
        val text = "abcdef"
        val source = "docs/a.md"
        val title = "a.md"
        val strategy = FixedSizeChunking(chunkSize = 4)

        // when
        val actual = strategy.chunk(doc(text, source = source, title = title))

        // then
        val meta = actual.first().metadata
        assertEquals(source, meta.source)
        assertEquals(title, meta.title)
        assertEquals(null, meta.section)
    }

    @Test
    fun `when multiple chunks emitted - then chunkId increments from zero in order`() {
        // given
        val text = "abcdefghij"
        val strategy = FixedSizeChunking(chunkSize = 4, overlap = 0)

        // when
        val actual = strategy.chunk(doc(text))

        // then
        assertEquals(listOf(0, 1, 2), actual.map { it.metadata.chunkId })
    }
    //endregion

    //region argument validation
    @Test
    fun `when chunkSize not positive - then IllegalArgumentException`() {
        // given - when - then
        assertFailsWith<IllegalArgumentException> { FixedSizeChunking(chunkSize = 0) }
    }

    @Test
    fun `when overlap negative - then IllegalArgumentException`() {
        // given - when - then
        assertFailsWith<IllegalArgumentException> { FixedSizeChunking(chunkSize = 4, overlap = -1) }
    }

    @Test
    fun `when overlap not smaller than chunkSize - then IllegalArgumentException`() {
        // given - when - then
        assertFailsWith<IllegalArgumentException> { FixedSizeChunking(chunkSize = 4, overlap = 4) }
    }
    //endregion
}
