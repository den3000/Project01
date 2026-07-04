package ru.den.writes.code.agenticHub.features.rag.chunking

import ru.den.writes.code.agenticHub.features.rag.doc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StructuralChunkingTest {

    //region sectioning
    @Test
    fun `when no headings - then single chunk with null section holds whole text`() {
        // given
        val text = "just some\ntext here"
        val strategy = StructuralChunking()

        // when
        val actual = strategy.chunk(doc(text))

        // then
        assertEquals(1, actual.size)
        assertEquals(text, actual.single().text)
        assertEquals(null, actual.single().metadata.section)
    }

    @Test
    fun `when headings split the doc - then one chunk per section`() {
        // given
        val titleSection = "# Title\nintro"
        val alphaSection = "## Alpha\nalpha body"
        val betaSection = "## Beta\nbeta body"
        val text = "$titleSection\n\n$alphaSection\n\n$betaSection"
        val strategy = StructuralChunking()

        // when
        val actual = strategy.chunk(doc(text))

        // then
        assertEquals(listOf(titleSection, alphaSection, betaSection), actual.map { it.text })
    }

    @Test
    fun `when chunk emitted - then section metadata is the heading text`() {
        // given
        val titleHeading = "Title"
        val alphaHeading = "Alpha"
        val betaHeading = "Beta"
        val text = "# $titleHeading\nintro\n\n## $alphaHeading\nalpha body\n\n## $betaHeading\nbeta body"
        val strategy = StructuralChunking()

        // when
        val actual = strategy.chunk(doc(text))

        // then
        assertEquals(listOf(titleHeading, alphaHeading, betaHeading), actual.map { it.metadata.section })
    }

    @Test
    fun `when sectioned - then chunk text includes its heading line`() {
        // given
        val heading = "## Alpha"
        val body = "alpha body"
        val text = "$heading\n$body"
        val strategy = StructuralChunking()

        // when
        val actual = strategy.chunk(doc(text))

        // then
        assertEquals(text, actual.single().text)
    }

    @Test
    fun `when text precedes first heading - then preamble is its own null-section chunk`() {
        // given
        val preamble = "preface text"
        val headHeading = "Head"
        val headSection = "# $headHeading\nbody"
        val text = "$preamble\n\n$headSection"
        val strategy = StructuralChunking()

        // when
        val actual = strategy.chunk(doc(text))

        // then
        assertEquals(preamble, actual.first().text)
        assertEquals(null, actual.first().metadata.section)
        assertEquals(headHeading, actual[1].metadata.section)
    }

    @Test
    fun `when heading has no body - then chunk holds just the heading line`() {
        // given
        val headingA = "# A"
        val sectionB = "# B\nbody"
        val text = "$headingA\n$sectionB"
        val strategy = StructuralChunking()

        // when
        val actual = strategy.chunk(doc(text))

        // then
        assertEquals(headingA, actual.first().text)
        assertEquals(sectionB, actual[1].text)
    }
    //endregion

    //region edges and ordering
    @Test
    fun `when blank preamble before first heading - then no empty leading chunk`() {
        // given
        val section = "# H\nb"
        val text = "\n\n$section"
        val strategy = StructuralChunking()

        // when
        val actual = strategy.chunk(doc(text))

        // then
        assertEquals(1, actual.size)
        assertEquals(0, actual.single().metadata.chunkId)
    }

    @Test
    fun `when hash without space - then not treated as heading`() {
        // given
        val text = "#NotAHeading still body"
        val strategy = StructuralChunking()

        // when
        val actual = strategy.chunk(doc(text))

        // then
        assertEquals(1, actual.size)
        assertEquals(null, actual.single().metadata.section)
    }

    @Test
    fun `when body blank - then no chunks`() {
        // given
        val text = "   \n\n  "
        val strategy = StructuralChunking()

        // when
        val actual = strategy.chunk(doc(text))

        // then
        assertTrue(actual.isEmpty())
    }

    @Test
    fun `when multiple sections emitted - then chunkId increments from zero in order`() {
        // given
        val sectionA = "# A\na"
        val sectionB = "# B\nb"
        val sectionC = "# C\nc"
        val text = "$sectionA\n\n$sectionB\n\n$sectionC"
        val strategy = StructuralChunking()

        // when
        val actual = strategy.chunk(doc(text))

        // then
        assertEquals(listOf(0, 1, 2), actual.map { it.metadata.chunkId })
    }
    //endregion
}
