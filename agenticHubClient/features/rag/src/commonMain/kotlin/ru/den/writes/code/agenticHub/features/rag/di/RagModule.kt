package ru.den.writes.code.agenticHub.features.rag.di

import org.koin.core.module.Module
import org.koin.dsl.module
import ru.den.writes.code.agenticHub.features.rag.ChunkingStrategy
import ru.den.writes.code.agenticHub.features.rag.Embedder
import ru.den.writes.code.agenticHub.features.rag.FakeEmbedder
import ru.den.writes.code.agenticHub.features.rag.IndexStore
import ru.den.writes.code.agenticHub.features.rag.IndexingPipeline
import ru.den.writes.code.agenticHub.features.rag.Retriever
import ru.den.writes.code.agenticHub.features.rag.VectorIndex

/**
 * Koin module for the RAG layer.
 *
 * The [ChunkingStrategy] (fixed-size vs structural) and the built [VectorIndex] are
 * runtime-derived, so they're factory parameters (`parametersOf`); the
 * [io.ktor.client.HttpClient] and [Embedder] come from the graph. [IndexStore] is a
 * stateless wrapper over the filesystem port → `single`.
 *
 * Note: this module does **not** bind an [Embedder]. The offline fake comes from
 * [ragTestModule]; the network-backed (Ollama) embedder will bind here later. Until
 * then, resolving [IndexingPipeline]/[Retriever] from a pure production graph is
 * incomplete by design — an embedder must be composed in first.
 */
public val ragModule: Module = module {
    single { IndexStore(fs = get()) }
    factory { (chunking: ChunkingStrategy) -> IndexingPipeline(chunking, embedder = get()) }
    factory { (index: VectorIndex) -> Retriever(embedder = get(), index = index) }
}

/**
 * Test counterpart of [ragModule]: binds [Embedder] to the deterministic,
 * network-free [FakeEmbedder]. Compose it alongside [ragModule] (and
 * `fileSystemTestModule` for [IndexStore]) in an integration graph. `factory` —
 * fresh fake per `get()`, tests independent. See agenticHubClient/DI.md.
 */
public val ragTestModule: Module = module {
    factory<Embedder> { FakeEmbedder() }
}
