package ru.den.writes.code.project01.cliJvm.agent

import ru.den.writes.code.project01.shared.llm.Message
import ru.den.writes.code.project01.shared.llm.Role
import kotlinx.coroutines.test.runTest
import ru.den.writes.code.project01.cliJvm.FactsExtractor
import ru.den.writes.code.project01.cliJvm.FakeLlmApi
import ru.den.writes.code.project01.cliJvm.StickyFacts
import ru.den.writes.code.project01.cliJvm.TestDb
import ru.den.writes.code.project01.cliJvm.db.HistoryStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentStickyFactsTest {

    @Test
    fun `when StickyFacts strategy used - then facts extracted and injected on subsequent turns`() = runTest {
        TestDb().use { harness ->
            // given
            val fakeApi = FakeLlmApi().apply {
                queueText("""{"k":"v1"}""") // extraction for turn 1 (opening prompt)
                queueText("r1")             // main reply turn 1
                queueText("""{"k":"v2"}""") // extraction for turn 2
                queueText("r2")             // main reply turn 2
            }
            val store = HistoryStore(harness.db.messageDao(), sessionId = "facts")
            val chat = newChat(prompt = "p1", session = "facts")

            // when
            runSessionForTest(
                cliArgs = chat,
                llmApi = fakeApi,
                historyStore = store,
                promptSource = stdinSource("p2\n/exit\n"),
                strategy = StickyFacts(keepLast = 2),
            )

            // then
            // Per turn: extraction call first, then the main turn → 4 calls.
            assertEquals(4, fakeApi.calls.size)
            // Extraction call (index 0): single USER message with the user text,
            // and NOT the facts frame (the extractor doesn't see it).
            assertEquals(1, fakeApi.calls[0].messages.size)
            assertEquals(Role.USER, fakeApi.calls[0].messages[0].role)
            assertTrue(fakeApi.calls[0].messages[0].text.contains("p1"))
            assertTrue(fakeApi.calls[0].messages.none { it.text.startsWith(FactsExtractor.FACTS_FRAME_PREFIX) })

            // Main turn 1 (index 1) leads with the facts pair just extracted.
            assertEquals(
                Message(Role.USER, FactsExtractor.FACTS_FRAME_PREFIX + """{"k":"v1"}"""),
                fakeApi.calls[1].messages[0],
            )
            assertEquals(Message(Role.ASSISTANT, FactsExtractor.FACTS_ACK_TEXT), fakeApi.calls[1].messages[1])
            assertEquals(Message(Role.USER, "p1"), fakeApi.calls[1].messages.last())

            // Main turn 2 (index 3) carries the updated facts + recent tail + new turn.
            val sent = fakeApi.calls[3].messages
            assertEquals(Message(Role.USER, FactsExtractor.FACTS_FRAME_PREFIX + """{"k":"v2"}"""), sent[0])
            assertEquals(Message(Role.USER, "p2"), sent.last())

            // Extraction is overhead, not a turn: only the two real exchanges count.
            assertEquals(2, store.stats.turns)
        }
    }

    @Test
    fun `when StickyFacts extraction returns non-json - then prior facts kept on next turn`() = runTest {
        TestDb().use { harness ->
            // given
            val fakeApi = FakeLlmApi().apply {
                queueText("""{"k":"v1"}""")      // extraction turn 1 → facts set
                queueText("r1")                  // main turn 1
                queueText("no json here, sorry") // extraction turn 2 → degrade to prior
                queueText("r2")                  // main turn 2
            }
            val store = HistoryStore(harness.db.messageDao(), sessionId = "degrade")
            val chat = newChat(prompt = "p1", session = "degrade")

            // when
            runSessionForTest(
                cliArgs = chat,
                llmApi = fakeApi,
                historyStore = store,
                promptSource = stdinSource("p2\n/exit\n"),
                strategy = StickyFacts(keepLast = 2),
            )

            // then
            assertEquals(4, fakeApi.calls.size)
            // Turn 2 still ships the PRIOR facts (extraction 2 was unusable),
            // and the real turn still goes out.
            assertEquals(
                Message(Role.USER, FactsExtractor.FACTS_FRAME_PREFIX + """{"k":"v1"}"""),
                fakeApi.calls[3].messages[0],
            )
            assertEquals(Message(Role.USER, "p2"), fakeApi.calls[3].messages.last())
        }
    }
}
