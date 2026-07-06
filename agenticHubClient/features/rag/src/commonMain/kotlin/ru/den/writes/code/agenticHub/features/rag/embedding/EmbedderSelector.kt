package ru.den.writes.code.agenticHub.features.rag.embedding

/**
 * Picks the concrete [Embedder] for an [EmbedderKind]. The composition root binds
 * one that knows how to build each backend (Ollama with the shared HttpClient,
 * Gemini with the API key) — rag stays credential-free; the key is the root's to
 * hold. Callers (`-rag add`, `/rag`) resolve an embedder per operation so index
 * build and query use the same one.
 */
public fun interface EmbedderSelector {
    public fun select(kind: EmbedderKind): Embedder
}
