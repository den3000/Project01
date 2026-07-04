package ru.den.writes.code.agenticHub.features.rag.di

import org.koin.core.module.Module
import org.koin.dsl.module
import ru.den.writes.code.agenticHub.features.rag.Retriever
import ru.den.writes.code.agenticHub.features.rag.chunking.ChunkingStrategy
import ru.den.writes.code.agenticHub.features.rag.embedding.Embedder
import ru.den.writes.code.agenticHub.features.rag.embedding.EmbedderFake
import ru.den.writes.code.agenticHub.features.rag.indexing.IndexStore
import ru.den.writes.code.agenticHub.features.rag.indexing.IndexingPipeline
import ru.den.writes.code.agenticHub.features.rag.indexing.VectorIndex

/**
 * Bindings shared by both [ragModule] and [ragTestModule] — the pieces that don't
 * differ between the production and test graphs. Private and pulled in via
 * `includes`, so the two public modules never duplicate (nor drift on) them.
 *
 * The [ChunkingStrategy] (fixed/token/structural) and the built [VectorIndex] are
 * runtime-derived → factory parameters (`parametersOf`); the [Embedder] comes from
 * the graph. Nothing here binds an [Embedder] — production will bind the network
 * (Ollama) one, tests bind [EmbedderFake] — so resolving [IndexingPipeline] /
 * [Retriever] needs an embedder composed in first.
 */
private val sharedRagModule: Module = module {
    factory { (chunking: ChunkingStrategy) -> IndexingPipeline(chunking, embedder = get()) }
    factory { (index: VectorIndex) -> Retriever(embedder = get(), index = index) }
}

/**
 * Production Koin module for the RAG layer: the shared pipeline/retriever bindings
 * ([sharedRagModule]) plus [IndexStore] as a `single` — one stateless wrapper over
 * the filesystem port per session. Does not bind an [Embedder] (see
 * [sharedRagModule]); the network-backed embedder will bind here later.
 */
public val ragModule: Module = module {
    includes(sharedRagModule)
    single { IndexStore(fs = get()) }
}

/**
 * Test counterpart of [ragModule]: the same shared pipeline/retriever bindings, but
 * [IndexStore] as a `factory` (fresh per `get()` → tests independent) and the
 * offline [EmbedderFake] bound as the [Embedder]. Compose it — with
 * `fileSystemTestModule` for [IndexStore]'s filesystem — **instead of** [ragModule]
 * in an integration graph. See agenticHubClient/DI.md.
 */
public val ragTestModule: Module = module {
    includes(sharedRagModule)
    factory { IndexStore(fs = get()) }
    factory<Embedder> { EmbedderFake() }
}
