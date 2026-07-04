package ru.den.writes.code.agenticHub.features.llm

import io.ktor.client.HttpClient
import org.koin.dsl.koinApplication
import ru.den.writes.code.agenticHub.features.llm.ollama.LocalOllamaApi
import ru.den.writes.code.agenticHub.features.rag.Retriever
import ru.den.writes.code.agenticHub.features.rag.chunking.SourceDocument
import ru.den.writes.code.agenticHub.features.rag.chunking.StructuralChunking
import ru.den.writes.code.agenticHub.features.rag.embedding.OllamaEmbedder
import ru.den.writes.code.agenticHub.features.rag.indexing.IndexingPipeline
import ru.den.writes.code.agenticHub.platform.network.di.networkModule
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Opt-in live test (see LIVE_TESTS.md): excluded unless `-PliveTests`. Needs a live Ollama
// with BOTH a generative tag (default gemma4:26b, -Dollama.chat.model=<tag>) and the embed
// model (`ollama pull nomic-embed-text`). This is the día-22 "first RAG query" comparison —
// same question answered WITH the retrieved index vs WITHOUT it — wired end-to-end across the
// full features:rag + features:llm modules. The formal 10-question suite is a later deliverable;
// this scaffolds the comparison on a few control questions.
class RagAnswerLiveTest {

    private val koin = koinApplication { modules(networkModule) }.koin

    // A small, fictional internal handbook — facts the base model cannot know, so the
    // difference between the RAG and no-RAG answers is unambiguous.
    private val handbook = SourceDocument(
        source = "handbook/zephyr.md",
        title = "Project Zephyr — Engineering Handbook",
        text = """
            # Project Zephyr — Engineering Handbook

            ## Code Review Policy
            Every merge request in Project Zephyr requires exactly 3 approvals before it can be
            merged. The maximum review turnaround (SLA) is 12 hours.

            ## Deployment Windows
            Production deploys for Project Zephyr are permitted only on Tuesdays and Thursdays,
            between 10:00 and 12:00 UTC. Deploys outside this window need VP sign-off.

            ## On-call Rotation
            The Project Zephyr on-call rotation lasts 5 days and is handed over every Monday at
            09:00 UTC.
        """.trimIndent(),
    )

    private val controlQuestions = listOf(
        "How many approvals does a Project Zephyr merge request require before merging?",
        "On which days is production deployment allowed for Project Zephyr?",
        "How long does the Project Zephyr on-call rotation last?",
    )

    @Test
    fun `when the same questions run with the index vs without - then both modes answer and RAG is grounded`() =
        liveOllamaTest(koin) {
            // given — real embedder + generative model, index built end-to-end via rag
            val httpClient = koin.get<HttpClient>()
            val embedder = OllamaEmbedder(httpClient)
            val chatApi = LocalOllamaApi(httpClient = httpClient, model = liveChatModel())
            val params = GenerationParams(temperature = 0.0, maxTokens = 160, thinkingBudget = 0)

            val index = IndexingPipeline(StructuralChunking(), embedder).index(listOf(handbook))
            val retriever = Retriever(embedder, index)

            println("=== día-22 RAG comparison (model=${liveChatModel().id}) ===")
            controlQuestions.forEachIndexed { i, question ->
                // when — retrieve, then answer WITH vs WITHOUT the retrieved context
                val chunks = retriever.retrieve(question, topK = 2)
                val withRag = chatApi.send(
                    listOf(ragChunksToContextMessage(chunks), Message(Role.USER, question)),
                    params,
                )
                val withoutRag = chatApi.send(listOf(Message(Role.USER, question)), params)

                // then — both modes must succeed and produce text
                assertNull(withRag.error, "RAG mode errored on q$i: ${withRag.error}")
                assertNull(withoutRag.error, "bare mode errored on q$i: ${withoutRag.error}")
                assertTrue(!withRag.text.isNullOrBlank(), "RAG answer empty on q$i")
                assertTrue(!withoutRag.text.isNullOrBlank(), "bare answer empty on q$i")

                println("\n[Q${i + 1}] $question")
                println("  top chunk: ${chunks.firstOrNull()?.chunk?.metadata?.section} " +
                    "(score=%.3f)".format(chunks.firstOrNull()?.score ?: 0.0))
                println("  no-RAG : ${withoutRag.text?.trim()?.replace("\n", " ")}")
                println("  + RAG  : ${withRag.text?.trim()?.replace("\n", " ")}")
            }

            // The first control question has a distinctive numeric answer (3 approvals) the
            // base model cannot know — the RAG answer should surface it. Primary RAG signal.
            val approvalsQ = controlQuestions[0]
            val ragAnswer = chatApi.send(
                listOf(ragChunksToContextMessage(retriever.retrieve(approvalsQ, topK = 2)), Message(Role.USER, approvalsQ)),
                params,
            ).text.orEmpty().lowercase()
            assertTrue(
                "3" in ragAnswer || "three" in ragAnswer,
                "RAG answer should surface the grounded '3 approvals' fact, was: $ragAnswer",
            )
        }
}
