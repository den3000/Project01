package ru.den.writes.code.agenticHub.features.rag.di

import org.koin.dsl.koinApplication
import ru.den.writes.code.agenticHub.features.rag.embedding.Embedder
import ru.den.writes.code.agenticHub.features.rag.embedding.OllamaEmbedder
import ru.den.writes.code.agenticHub.platform.network.di.networkModule
import kotlin.test.Test
import kotlin.test.assertIs

// Offline (no network): only constructs the graph and resolves the Embedder binding.
// jvmTest because it needs networkModule's real HttpClient (Java engine, JVM). Not a
// *OllamaLiveTest → runs in the normal jvmTest suite.
class RagModuleOllamaBindingTest {

    @Test
    fun `when ragModule composed with networkModule - then Embedder resolves to OllamaEmbedder`() {
        // given
        val app = koinApplication { modules(ragModule, networkModule) }

        // when
        val actual = app.koin.get<Embedder>()

        // then
        assertIs<OllamaEmbedder>(actual)
        app.close()
    }
}
