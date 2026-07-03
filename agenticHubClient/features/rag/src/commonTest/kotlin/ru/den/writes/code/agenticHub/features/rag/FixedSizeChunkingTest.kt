package ru.den.writes.code.agenticHub.features.rag

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FixedSizeChunkingTest {

    //region windowing
    @Test
    fun `when body shorter than chunkSize - then single chunk holds whole text`() {
        // given
        val strategy = FixedSizeChunking(chunkSize = 10)

        // when
        val actual = strategy.chunk(doc("abc"))

        // then
        assertEquals(1, actual.size)
        assertEquals("abc", actual.single().text)
    }

    @Test
    fun `when body length equals chunkSize - then single chunk and no empty tail`() {
        // given
        val strategy = FixedSizeChunking(chunkSize = 4)

        // when
        val actual = strategy.chunk(doc("abcd"))

        // then
        assertEquals(listOf("abcd"), actual.map { it.text })
    }

    @Test
    fun `when no overlap - then windows tile the text end to end`() {
        // given
        val strategy = FixedSizeChunking(chunkSize = 4, overlap = 0)

        // when
        val actual = strategy.chunk(doc("abcdefghij"))

        // then
        assertEquals(listOf("abcd", "efgh", "ij"), actual.map { it.text })
    }

    @Test
    fun `when length is exact multiple of chunkSize - then no trailing empty chunk`() {
        // given
        val strategy = FixedSizeChunking(chunkSize = 4, overlap = 0)

        // when
        val actual = strategy.chunk(doc("abcdefgh"))

        // then
        assertEquals(listOf("abcd", "efgh"), actual.map { it.text })
    }

    @Test
    fun `when overlap set - then consecutive chunks share overlap chars`() {
        // given
        val strategy = FixedSizeChunking(chunkSize = 4, overlap = 1)

        // when
        val actual = strategy.chunk(doc("abcdefghij"))

        // then
        assertEquals(listOf("abcd", "defg", "ghij"), actual.map { it.text })
    }

    @Test
    fun `when body blank - then no chunks`() {
        // given
        val strategy = FixedSizeChunking(chunkSize = 4)

        // when
        val actual = strategy.chunk(doc("   "))

        // then
        assertTrue(actual.isEmpty())
    }
    //endregion

    //region metadata
    @Test
    fun `when chunked - then metadata carries source and title with null section`() {
        // given
        val strategy = FixedSizeChunking(chunkSize = 4)

        // when
        val actual = strategy.chunk(doc("abcdef", source = "docs/a.md", title = "a.md"))

        // then
        val meta = actual.first().metadata
        assertEquals("docs/a.md", meta.source)
        assertEquals("a.md", meta.title)
        assertEquals(null, meta.section)
    }

    @Test
    fun `when multiple chunks emitted - then chunkId increments from zero in order`() {
        // given
        val strategy = FixedSizeChunking(chunkSize = 4, overlap = 0)

        // when
        val actual = strategy.chunk(doc("abcdefghij"))

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

    private fun doc(
        text: String,
        source: String = "src",
        title: String = "title",
    ): SourceDocument = SourceDocument(source = source, title = title, text = text)
}
