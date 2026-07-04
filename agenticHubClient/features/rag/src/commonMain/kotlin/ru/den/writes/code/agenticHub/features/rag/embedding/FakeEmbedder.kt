package ru.den.writes.code.agenticHub.features.rag.embedding

import kotlin.math.sqrt

/**
 * Deterministic, network-free [Embedder] for offline tests: a bag-of-words hashing
 * embedder. Each token is hashed into one of [dimensions] buckets and increments
 * it, then the vector is L2-normalized. Two texts that share words land close in
 * cosine space and unrelated texts land apart — enough signal to exercise the
 * indexing/retrieval pipeline believably without a real model.
 *
 * `internal` — reachable only via
 * [ragTestModule][ru.den.writes.code.agenticHub.features.rag.di.ragTestModule],
 * under the [Embedder] interface, next to the real code (the Ollama impl). Not a
 * production embedder: token hashing collides and ignores word order.
 */
internal class FakeEmbedder(private val dimensions: Int = 64) : Embedder {
    override suspend fun embed(texts: List<String>): List<List<Float>> =
        texts.map { embedOne(it) }

    private fun embedOne(text: String): List<Float> {
        val vector = DoubleArray(dimensions)
        for (token in tokenize(text)) {
            vector[token.hashCode().mod(dimensions)] += 1.0
        }
        val norm = sqrt(vector.sumOf { it * it })
        return if (norm == 0.0) vector.map { 0f } else vector.map { (it / norm).toFloat() }
    }

    private fun tokenize(text: String): List<String> =
        text.lowercase().split(Regex("\\W+")).filter { it.isNotEmpty() }
}
