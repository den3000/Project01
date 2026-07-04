package ru.den.writes.code.agenticHub.features.rag.chunking

import ru.den.writes.code.agenticHub.features.rag.doc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChunkingComparisonTest {

    @Test
    fun `when comparing two strategies - then report carries stats per strategy in order`() {
        // given
        val strategies = mapOf(
            "fixed" to FixedSizeChunking(chunkSize = 1000),
            "structural" to StructuralChunking(),
        )
        val markdownText = "# A\nalpha\n\n# B\nbeta\n\n# C\ngamma"
        val markdownDoc = doc(source = "src", title = "doc.md", text = markdownText)

        // when
        val actual = ChunkingComparison.compare(markdownDoc, strategies)

        // then
        assertEquals(listOf("fixed", "structural"), actual.stats.map { it.strategyName })
    }

    @Test
    fun `when fixed size covers whole doc but structural splits - then chunk counts differ`() {
        // given
        val strategies = mapOf(
            "fixed" to FixedSizeChunking(chunkSize = 1000),
            "structural" to StructuralChunking(),
        )
        val markdownText = "# A\nalpha\n\n# B\nbeta\n\n# C\ngamma"
        val markdownDoc = doc(source = "src", title = "doc.md", text = markdownText)

        // when
        val actual = ChunkingComparison.compare(markdownDoc, strategies)

        // then
        assertEquals(1, actual.stats.first { it.strategyName == "fixed" }.chunkCount)
        assertEquals(3, actual.stats.first { it.strategyName == "structural" }.chunkCount)
    }

    @Test
    fun `when strategy yields uneven chunks - then stats capture count min max and avg`() {
        // given
        val strategies = mapOf("fixed" to FixedSizeChunking(chunkSize = 4, overlap = 0))

        // when
        val actual = ChunkingComparison.compare(doc("abcdefghij"), strategies).stats.single()

        // then
        assertEquals(3, actual.chunkCount)
        assertEquals(2, actual.minChars)
        assertEquals(4, actual.maxChars)
        assertEquals(3.333, actual.avgChars, absoluteTolerance = 0.01)
    }

    @Test
    fun `when no strategies supplied - then report has no stats`() {
        // given
        val strategies = emptyMap<String, ChunkingStrategy>()
        val markdownText = "# A\nalpha\n\n# B\nbeta\n\n# C\ngamma"
        val markdownDoc = doc(source = "src", title = "doc.md", text = markdownText)

        // when
        val actual = ChunkingComparison.compare(markdownDoc, strategies)

        // then
        assertTrue(actual.stats.isEmpty())
    }

    @Test
    fun `when rendered - then output names each strategy with its chunk count`() {
        // given
        val markdownText = "# A\nalpha\n\n# B\nbeta\n\n# C\ngamma"
        val markdownDoc = doc(source = "src", title = "doc.md", text = markdownText)
        val report = ChunkingComparison.compare(
            markdownDoc,
            mapOf(
                "fixed" to FixedSizeChunking(chunkSize = 1000),
                "structural" to StructuralChunking(),
            ),
        )

        // when
        val actual = report.render()

        // then
        assertTrue(actual.contains("fixed: 1 chunks"))
        assertTrue(actual.contains("structural: 3 chunks"))
    }
}
