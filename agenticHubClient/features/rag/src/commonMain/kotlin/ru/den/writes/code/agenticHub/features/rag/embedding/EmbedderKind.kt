package ru.den.writes.code.agenticHub.features.rag.embedding

/**
 * Which embedding backend a RAG operation uses. [OLLAMA] is the local default
 * (free, offline); [GEMINI] is the cloud embedder (needs an API key, burns quota).
 * An index built with one kind MUST be queried with the same kind — vectors from
 * different models/dimensions are not comparable (see the rag README грабли).
 */
public enum class EmbedderKind { OLLAMA, GEMINI }
