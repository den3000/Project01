package ru.den.writes.code.agenticHub.features.rag.chunking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StructuralChunkingTest {

    //region sectioning
    @Test
    fun `when no headings - then single chunk with null section holds whole text`() {
        // given
        val strategy = StructuralChunking()

        // when
        val actual = strategy.chunk(doc("just some\ntext here"))

        // then
        assertEquals(1, actual.size)
        assertEquals("just some\ntext here", actual.single().text)
        assertEquals(null, actual.single().metadata.section)
    }

    @Test
    fun `when headings split the doc - then one chunk per section`() {
        // given
        val strategy = StructuralChunking()

        // when
        val actual = strategy.chunk(doc("# Title\nintro\n\n## Alpha\nalpha body\n\n## Beta\nbeta body"))

        // then
        assertEquals(3, actual.size)
    }

    @Test
    fun `when chunk emitted - then section metadata is the heading text`() {
        // given
        val strategy = StructuralChunking()

        // when
        val actual = strategy.chunk(doc("# Title\nintro\n\n## Alpha\nalpha body\n\n## Beta\nbeta body"))

        // then
        assertEquals(listOf("Title", "Alpha", "Beta"), actual.map { it.metadata.section })
    }

    @Test
    fun `when sectioned - then chunk text includes its heading line`() {
        // given
        val strategy = StructuralChunking()

        // when
        val actual = strategy.chunk(doc("## Alpha\nalpha body"))

        // then
        assertEquals("## Alpha\nalpha body", actual.single().text)
    }

    @Test
    fun `when text precedes first heading - then preamble is its own null-section chunk`() {
        // given
        val strategy = StructuralChunking()

        // when
        val actual = strategy.chunk(doc("preface text\n\n# Head\nbody"))

        // then
        assertEquals("preface text", actual.first().text)
        assertEquals(null, actual.first().metadata.section)
        assertEquals("Head", actual[1].metadata.section)
    }

    @Test
    fun `when heading has no body - then chunk holds just the heading line`() {
        // given
        val strategy = StructuralChunking()

        // when
        val actual = strategy.chunk(doc("# A\n# B\nbody"))

        // then
        assertEquals("# A", actual.first().text)
        assertEquals("# B\nbody", actual[1].text)
    }
    //endregion

    //region edges and ordering
    @Test
    fun `when blank preamble before first heading - then no empty leading chunk`() {
        // given
        val strategy = StructuralChunking()

        // when
        val actual = strategy.chunk(doc("\n\n# H\nb"))

        // then
        assertEquals(1, actual.size)
        assertEquals(0, actual.single().metadata.chunkId)
    }

    @Test
    fun `when hash without space - then not treated as heading`() {
        // given
        val strategy = StructuralChunking()

        // when
        val actual = strategy.chunk(doc("#NotAHeading still body"))

        // then
        assertEquals(1, actual.size)
        assertEquals(null, actual.single().metadata.section)
    }

    @Test
    fun `when body blank - then no chunks`() {
        // given
        val strategy = StructuralChunking()

        // when
        val actual = strategy.chunk(doc("   \n\n  "))

        // then
        assertTrue(actual.isEmpty())
    }

    @Test
    fun `when multiple sections emitted - then chunkId increments from zero in order`() {
        // given
        val strategy = StructuralChunking()

        // when
        val actual = strategy.chunk(doc("# A\na\n\n# B\nb\n\n# C\nc"))

        // then
        assertEquals(listOf(0, 1, 2), actual.map { it.metadata.chunkId })
    }
    //endregion

    private fun doc(
        text: String,
        source: String = "src",
        title: String = "title",
    ): SourceDocument = SourceDocument(source = source, title = title, text = text)
}
