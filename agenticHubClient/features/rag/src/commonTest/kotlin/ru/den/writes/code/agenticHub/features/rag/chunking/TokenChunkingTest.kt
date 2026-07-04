package ru.den.writes.code.agenticHub.features.rag.chunking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TokenChunkingTest {

    //region windowing
    @Test
    fun `when fewer tokens than window - then single chunk holds the trimmed text`() {
        // given
        val strategy = TokenChunking(tokensPerChunk = 5)

        // when
        val actual = strategy.chunk(doc("  one two three  "))

        // then
        assertEquals(listOf("one two three"), actual.map { it.text })
    }

    @Test
    fun `when no overlap - then windows tile by whole tokens`() {
        // given
        val strategy = TokenChunking(tokensPerChunk = 2, overlap = 0)

        // when
        val actual = strategy.chunk(doc("one two three four five"))

        // then
        assertEquals(listOf("one two", "three four", "five"), actual.map { it.text })
    }

    @Test
    fun `when token count is exact multiple - then no trailing empty chunk`() {
        // given
        val strategy = TokenChunking(tokensPerChunk = 2, overlap = 0)

        // when
        val actual = strategy.chunk(doc("one two three four"))

        // then
        assertEquals(listOf("one two", "three four"), actual.map { it.text })
    }

    @Test
    fun `when overlap set - then consecutive chunks share tokens`() {
        // given
        val strategy = TokenChunking(tokensPerChunk = 2, overlap = 1)

        // when
        val actual = strategy.chunk(doc("one two three four five"))

        // then
        assertEquals(
            listOf("one two", "two three", "three four", "four five"),
            actual.map { it.text },
        )
    }
    //endregion

    //region token boundaries
    @Test
    fun `when tokenized - then no chunk splits a word`() {
        // given
        val strategy = TokenChunking(tokensPerChunk = 2, overlap = 1)

        // when
        val actual = strategy.chunk(doc("alpha beta gamma"))

        // then
        val words = setOf("alpha", "beta", "gamma")
        val emitted = actual.flatMap { it.text.split(Regex("\\s+")) }.toSet()
        assertTrue(words.containsAll(emitted), "emitted non-word fragments: ${emitted - words}")
    }

    @Test
    fun `when window spans a newline - then inner whitespace is preserved`() {
        // given
        val strategy = TokenChunking(tokensPerChunk = 4)

        // when
        val actual = strategy.chunk(doc("one two\nthree four"))

        // then
        assertEquals("one two\nthree four", actual.single().text)
    }
    //endregion

    //region metadata and edges
    @Test
    fun `when chunked - then metadata carries source and title with null section`() {
        // given
        val strategy = TokenChunking(tokensPerChunk = 2)

        // when
        val actual = strategy.chunk(doc("one two three", source = "docs/a.md", title = "a.md"))

        // then
        val meta = actual.first().metadata
        assertEquals("docs/a.md", meta.source)
        assertEquals("a.md", meta.title)
        assertEquals(null, meta.section)
    }

    @Test
    fun `when multiple chunks emitted - then chunkId increments from zero in order`() {
        // given
        val strategy = TokenChunking(tokensPerChunk = 2, overlap = 0)

        // when
        val actual = strategy.chunk(doc("one two three four five"))

        // then
        assertEquals(listOf(0, 1, 2), actual.map { it.metadata.chunkId })
    }

    @Test
    fun `when body blank - then no chunks`() {
        // given
        val strategy = TokenChunking(tokensPerChunk = 2)

        // when
        val actual = strategy.chunk(doc("   \n  "))

        // then
        assertTrue(actual.isEmpty())
    }
    //endregion

    //region argument validation
    @Test
    fun `when tokensPerChunk not positive - then IllegalArgumentException`() {
        // given - when - then
        assertFailsWith<IllegalArgumentException> { TokenChunking(tokensPerChunk = 0) }
    }

    @Test
    fun `when overlap negative - then IllegalArgumentException`() {
        // given - when - then
        assertFailsWith<IllegalArgumentException> { TokenChunking(tokensPerChunk = 2, overlap = -1) }
    }

    @Test
    fun `when overlap not smaller than tokensPerChunk - then IllegalArgumentException`() {
        // given - when - then
        assertFailsWith<IllegalArgumentException> { TokenChunking(tokensPerChunk = 2, overlap = 2) }
    }
    //endregion
}
