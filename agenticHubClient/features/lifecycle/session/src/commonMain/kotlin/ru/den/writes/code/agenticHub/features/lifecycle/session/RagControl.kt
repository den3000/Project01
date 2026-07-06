package ru.den.writes.code.agenticHub.features.lifecycle.session

import ru.den.writes.code.agenticHub.features.rag.Retriever
import ru.den.writes.code.agenticHub.features.rag.embedding.EmbedderKind
import ru.den.writes.code.agenticHub.features.rag.embedding.EmbedderSelector
import ru.den.writes.code.agenticHub.features.rag.indexing.IndexStore

/**
 * The RAG index a session is currently retrieving against: its [name] (for status
 * lines), the built [retriever] the turn engine queries, which [embedder] backend it
 * uses, and how many chunks to pull per turn ([topK]).
 */
public data class ActiveRag(
    val name: String,
    val retriever: Retriever,
    val embedder: EmbedderKind,
    val topK: Int,
)

/**
 * Live control surface for in-session RAG, mirroring [scheduling.SchedulerControl]:
 * `/rag <name>` loads a saved index off disk and arms retrieval, `/rag off` detaches
 * it, `/rag` reports status. Holds the single mutable [active] slot the turn engine
 * reads each turn — the same instance is shared by [CommandRunner] (writes) and the
 * engine (reads), so a load takes effect on the very next turn.
 *
 * Loading only decodes JSON (no network); the embedding call happens later, per turn,
 * inside [Retriever.retrieve]. Index files live under [ragRoot] as `<name>.json`,
 * written by the startup `-rag add` command.
 */
public class RagControl(
    private val indexStore: IndexStore,
    private val embedderSelector: EmbedderSelector,
    private val ragRoot: String,
    private val defaultKind: EmbedderKind,
    private val topK: Int = DEFAULT_TOP_K,
) {
    public var active: ActiveRag? = null
        private set

    /**
     * Load the index named [name] and arm retrieval with [kind] (null → the session
     * default derived at startup), or explain why it's absent. The embedder MUST match
     * the one the index was built with — mismatched vectors just retrieve noise.
     */
    public fun load(name: String, kind: EmbedderKind? = null): String {
        val use = kind ?: defaultKind
        val index = indexStore.load("$ragRoot/$name.json")
            ?: return "[rag] no index '$name' — build it first with -rag add $name src <file>"
        active = ActiveRag(name, Retriever(embedderSelector.select(use), index), use, topK)
        return "[rag] loaded '$name' (${index.chunks.size} chunk(s), ${use.name.lowercase()}, topK=$topK)"
    }

    /** Detach the active index — later turns stop using retrieval. */
    public fun off(): String {
        val was = active?.name ?: return "[rag] no active index"
        active = null
        return "[rag] '$was' detached — answers no longer use retrieval"
    }

    /** One-line status for a bare `/rag`. */
    public fun status(): String =
        active?.let { "[rag] active '${it.name}' (${it.embedder.name.lowercase()}, topK=${it.topK})" }
            ?: "[rag] no active index — /rag <name> to load one"

    public companion object {
        /** Chunks retrieved per turn by default — the lecture's "top-K" pull. */
        public const val DEFAULT_TOP_K: Int = 5
    }
}
