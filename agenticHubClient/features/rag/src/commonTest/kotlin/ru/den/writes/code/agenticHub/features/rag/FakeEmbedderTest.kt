package ru.den.writes.code.agenticHub.features.rag

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakeEmbedderTest {

    @Test
    fun `when same text embedded twice - then vectors are identical`() = runTest {
        // given
        val embedder = FakeEmbedder()

        // when
        val actual = embedder.embed(listOf("cosine similarity search", "cosine similarity search"))

        // then
        assertEquals(actual[0], actual[1])
    }

    @Test
    fun `when texts share words - then their cosine exceeds an unrelated pair`() = runTest {
        // given
        val embedder = FakeEmbedder()
        val vectors = embedder.embed(
            listOf(
                "vector search over embeddings",
                "embeddings power vector search",
                "the cat slept on the warm windowsill",
            ),
        )

        // when
        val related = cosineSimilarity(vectors[0], vectors[1])
        val unrelated = cosineSimilarity(vectors[0], vectors[2])

        // then
        assertTrue(related > unrelated, "related=$related should exceed unrelated=$unrelated")
    }

    @Test
    fun `when batch embedded - then output aligns positionally with input`() = runTest {
        // given
        val embedder = FakeEmbedder()
        val texts = listOf("alpha", "beta", "gamma")

        // when
        val actual = embedder.embed(texts)

        // then
        assertEquals(texts.size, actual.size)
        assertEquals(embedder.embed(listOf("beta")).single(), actual[1])
    }

    @Test
    fun `when embedded - then vector has the configured dimension`() = runTest {
        // given
        val embedder = FakeEmbedder(dimensions = 32)

        // when
        val actual = embedder.embed(listOf("some text")).single()

        // then
        assertEquals(32, actual.size)
    }
}
