package ru.den.writes.code.agenticHub.features.rag

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CosineSimilarityTest {

    @Test
    fun `when vectors identical - then similarity is one`() {
        // given
        val v = listOf(1f, 2f, 3f)

        // when
        val actual = cosineSimilarity(v, v)

        // then
        assertEquals(1.0, actual, absoluteTolerance = 1e-9)
    }

    @Test
    fun `when vectors orthogonal - then similarity is zero`() {
        // given
        val a = listOf(1f, 0f)
        val b = listOf(0f, 1f)

        // when
        val actual = cosineSimilarity(a, b)

        // then
        assertEquals(0.0, actual, absoluteTolerance = 1e-9)
    }

    @Test
    fun `when vectors opposite - then similarity is minus one`() {
        // given
        val a = listOf(1f, 1f)
        val b = listOf(-1f, -1f)

        // when
        val actual = cosineSimilarity(a, b)

        // then
        assertEquals(-1.0, actual, absoluteTolerance = 1e-9)
    }

    @Test
    fun `when one vector is zero - then similarity is zero`() {
        // given
        val a = listOf(0f, 0f)
        val b = listOf(1f, 2f)

        // when
        val actual = cosineSimilarity(a, b)

        // then
        assertEquals(0.0, actual)
    }

    @Test
    fun `when vector lengths differ - then IllegalArgumentException`() {
        // given
        val a = listOf(1f, 2f)
        val b = listOf(1f)

        // when - then
        assertFailsWith<IllegalArgumentException> { cosineSimilarity(a, b) }
    }
}
