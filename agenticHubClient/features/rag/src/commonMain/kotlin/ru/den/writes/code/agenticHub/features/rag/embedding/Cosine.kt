package ru.den.writes.code.agenticHub.features.rag.embedding

import kotlin.math.sqrt

/**
 * Cosine similarity of two equal-length vectors: `1` when they point the same way
 * (semantically close), `0` when orthogonal (unrelated), `-1` when opposite — the
 * scale the whole retrieval step ranks on. A zero-magnitude vector has no
 * direction, so similarity against it is defined here as `0` (rather than NaN from
 * dividing by zero).
 *
 * @throws IllegalArgumentException if the vectors differ in length.
 */
public fun cosineSimilarity(a: List<Float>, b: List<Float>): Double {
    require(a.size == b.size) { "vectors differ in length: ${a.size} vs ${b.size}" }
    var dot = 0.0
    var normA = 0.0
    var normB = 0.0
    for (i in a.indices) {
        val x = a[i].toDouble()
        val y = b[i].toDouble()
        dot += x * y
        normA += x * x
        normB += y * y
    }
    if (normA == 0.0 || normB == 0.0) return 0.0
    return dot / (sqrt(normA) * sqrt(normB))
}
