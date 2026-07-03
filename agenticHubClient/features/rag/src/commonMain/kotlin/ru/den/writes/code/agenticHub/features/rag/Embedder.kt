package ru.den.writes.code.agenticHub.features.rag

/**
 * Maps texts to dense vectors for semantic search. Batched (one call, many texts)
 * because embedding backends amortize far better over a batch than per-string, and
 * `suspend` because the real backend (Ollama over HTTP) is I/O-bound. The returned
 * list is positionally aligned with [texts] — index `i` of the result is the vector
 * for `texts[i]`, and every vector shares one dimensionality.
 *
 * A deterministic fake drives the offline tests; an Ollama-backed implementation
 * lands behind this same interface without touching callers.
 */
public fun interface Embedder {
    public suspend fun embed(texts: List<String>): List<List<Float>>
}
