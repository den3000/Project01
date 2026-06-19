package ru.den.writes.code.project01.cliJvm.agent

import ru.den.writes.code.project01.shared.context.HistoryCompressor
import ru.den.writes.code.project01.shared.llm.LlmResult
import ru.den.writes.code.project01.shared.llm.Message
import ru.den.writes.code.project01.shared.llm.Role
import ru.den.writes.code.project01.shared.llm.Usage
import kotlinx.coroutines.test.runTest
import ru.den.writes.code.project01.cliJvm.SessionLoop
import ru.den.writes.code.project01.cliJvm.ChunkedFilePromptSource
import ru.den.writes.code.project01.cliJvm.ContextStrategy
import ru.den.writes.code.project01.cliJvm.FakeLlmApi
import ru.den.writes.code.project01.cliJvm.TestDb
import ru.den.writes.code.project01.cliJvm.db.HistoryStore
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentCompressionTest {

    @Test
    fun `when compression triggers - then old turns folded into summary pair and request shrinks`() = runTest {
        TestDb().use { harness ->
            // given
            val fakeApi = FakeLlmApi().apply {
                queueText("r1")
                queueText("r2")
                queueText("ROLLING SUMMARY") // summarization call during send 3
                queueText("r3")
            }
            val store = HistoryStore(harness.db.messageDao(), sessionId = "comp")
            val chat = newChat(prompt = "p1", session = "comp")
            val compressor = HistoryCompressor(keepLast = 2, summarizeEvery = 2)

            // when
            SessionLoop(
                cliArgs = chat,
                llmApi = fakeApi,
                historyStore = store,
                promptSource = stdinSource("p2\np3\n/exit\n"),
                strategy = ContextStrategy.Summary(compressor),
            ).run()

            // then
            // 3 real turns + 1 summarization call.
            assertEquals(4, fakeApi.calls.size)

            // The summarization call (index 2): one USER message carrying the
            // folded turns (p1 / r1).
            assertEquals(1, fakeApi.calls[2].messages.size)
            assertEquals(Role.USER, fakeApi.calls[2].messages[0].role)
            assertTrue(fakeApi.calls[2].messages[0].text.contains("p1"))

            // The third real turn (index 3) leads with the synthetic summary
            // pair, then the recent tail, then the current user turn.
            val sent = fakeApi.calls[3].messages
            assertEquals(
                Message(Role.USER, HistoryCompressor.SUMMARY_FRAME_PREFIX + "ROLLING SUMMARY"),
                sent[0],
            )
            assertEquals(Message(Role.ASSISTANT, HistoryCompressor.ACK_TEXT), sent[1])
            assertEquals(Message(Role.USER, "p3"), sent.last())
            // The folded prefix is gone; the request is shorter than full history.
            assertTrue(sent.none { it.text == "p1" || it.text == "r1" })
            // Strict role alternation on the wire list (Gemini contract).
            assertEquals(Role.USER, sent.first().role)
            sent.zipWithNext().forEach { (a, b) -> assertTrue(a.role != b.role) }
        }
    }

    @Test
    fun `when summarization fails - then degrades to full tail and the real turn still goes out`() = runTest {
        TestDb().use { harness ->
            // given
            val fakeApi = FakeLlmApi().apply {
                queueText("r1")
                queueText("r2")
                queue(LlmResult(text = null, error = "summarizer boom")) // compaction fails
                queueText("r3")
            }
            val store = HistoryStore(harness.db.messageDao(), sessionId = "degrade")
            val chat = newChat(prompt = "p1", session = "degrade")
            val compressor = HistoryCompressor(keepLast = 2, summarizeEvery = 2)

            // when
            SessionLoop(
                cliArgs = chat,
                llmApi = fakeApi,
                historyStore = store,
                promptSource = stdinSource("p2\np3\n/exit\n"),
                strategy = ContextStrategy.Summary(compressor),
            ).run()

            // then
            assertEquals(4, fakeApi.calls.size)
            // The third real turn carried the FULL history (no summary frame).
            val expected = listOf(
                Message(Role.USER, "p1"),
                Message(Role.ASSISTANT, "r1"),
                Message(Role.USER, "p2"),
                Message(Role.ASSISTANT, "r2"),
                Message(Role.USER, "p3"),
            )
            assertEquals(expected, fakeApi.calls[3].messages)
            // State never advanced → nothing persisted.
            assertNull(store.loadSummary())
        }
    }

    @Test
    fun `when resumed with persisted summary - then used directly without re-summarizing covered messages`() = runTest {
        TestDb().use { harness ->
            // given
            val dao = harness.db.messageDao()
            // Seed prior history + a persisted summary covering the first pair.
            val seed = HistoryStore(dao, sessionId = "resume")
            seed.append(Message(Role.USER, "p1"))
            seed.append(Message(Role.ASSISTANT, "r1"))
            seed.append(Message(Role.USER, "p2"))
            seed.append(Message(Role.ASSISTANT, "r2"))
            seed.saveSummary(
                summaryText = "PRIOR SUMMARY",
                coveredCount = 2,
                modelId = "gemini-2.5-flash-lite",
                usage = Usage(promptTokens = 100, outputTokens = 10, totalTokens = 110),
            )
            // Fresh run: summarizeEvery high enough that no compaction fires.
            val fakeApi = FakeLlmApi().apply { queueText("r3") }
            val store = HistoryStore(dao, sessionId = "resume")
            val chat = newChat(prompt = "p3", session = "resume")
            val compressor = HistoryCompressor(keepLast = 2, summarizeEvery = 100)

            // when
            SessionLoop(
                cliArgs = chat,
                llmApi = fakeApi,
                historyStore = store,
                promptSource = stdinSource("/exit\n"),
                strategy = ContextStrategy.Summary(compressor),
            ).run()

            // then
            // Only the single real turn — no summarization call.
            assertEquals(1, fakeApi.calls.size)
            val sent = fakeApi.calls[0].messages
            assertEquals(
                Message(Role.USER, HistoryCompressor.SUMMARY_FRAME_PREFIX + "PRIOR SUMMARY"),
                sent[0],
            )
            assertEquals(Message(Role.ASSISTANT, HistoryCompressor.ACK_TEXT), sent[1])
            // Covered prefix (p1 / r1) must NOT appear in the request.
            assertTrue(sent.none { it.text == "p1" || it.text == "r1" })
            assertEquals(Message(Role.USER, "p2"), sent[2])
            assertEquals(Message(Role.ASSISTANT, "r2"), sent[3])
            assertEquals(Message(Role.USER, "p3"), sent.last())
        }
    }

    @Test
    fun `when compression runs in feed mode - then folds chunks across compactions`() = runTest {
        TestDb().use { harness ->
            // given
            val fakeApi = FakeLlmApi().apply {
                queueText("r-open")   // opening prompt
                queueText("r-c1")     // chunk 1
                queueText("SUMMARY")  // compaction before the chunk-2 turn
                queueText("r-c2")     // chunk 2
            }
            val store = HistoryStore(harness.db.messageDao(), sessionId = "feedcomp")
            val chat = newChat(prompt = "open", session = "feedcomp")
            val compressor = HistoryCompressor(keepLast = 2, summarizeEvery = 2)
            val feed = ChunkedFilePromptSource(
                reader = StringReader("AAABBB"), // 2 chunks × 3 chars
                chunkChars = 3,
                instruction = "",
            )

            // when
            SessionLoop(
                cliArgs = chat,
                llmApi = fakeApi,
                historyStore = store,
                promptSource = feed,
                strategy = ContextStrategy.Summary(compressor),
            ).run()

            // then
            // open + chunk1 + (compaction) + chunk2 = 4 calls.
            assertEquals(4, fakeApi.calls.size)
            // The chunk-2 turn (index 3) carries the summary pair.
            assertEquals(
                Message(Role.USER, HistoryCompressor.SUMMARY_FRAME_PREFIX + "SUMMARY"),
                fakeApi.calls[3].messages[0],
            )
        }
    }
}
