package ru.den.writes.code.project01.cliJvm.agent

import ru.den.writes.code.project01.shared.llm.LlmResult
import ru.den.writes.code.project01.shared.llm.Message
import ru.den.writes.code.project01.shared.llm.Role
import ru.den.writes.code.project01.shared.llm.Usage
import kotlinx.coroutines.test.runTest
import ru.den.writes.code.project01.cliJvm.SessionLoop
import ru.den.writes.code.project01.cliJvm.FakeLlmApi
import ru.den.writes.code.project01.cliJvm.TestDb
import ru.den.writes.code.project01.cliJvm.db.HistoryStore
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentTokenAccountingTest {

    @Test
    fun `when turn succeeds - then usage folded into HistoryStore stats`() = runTest {
        TestDb().use { harness ->
            // given
            val fakeApi = FakeLlmApi().apply {
                queue(
                    LlmResult(
                        text = "ok",
                        usage = Usage(
                            promptTokens = 12,
                            outputTokens = 7,
                            thoughtsTokens = 3,
                            totalTokens = 22,
                        ),
                    )
                )
            }
            val store = HistoryStore(harness.db.messageDao(), sessionId = "tally")
            val chat = newChat(prompt = "hi", session = "tally")

            // when
            SessionLoop(chat, fakeApi, store, promptSource = stdinSource("/exit\n")).run()

            // then
            assertEquals(1, store.stats.turns)
            assertEquals(12, store.stats.totalPromptTokens)
            assertEquals(7, store.stats.totalOutputTokens)
            assertEquals(3, store.stats.totalThoughtsTokens)
        }
    }

    @Test
    fun `when Agent resumed after persisted turn - then stats reseeded and new turn added`() = runTest {
        TestDb().use { harness ->
            // given
            // Phase 1: persist a turn with token data.
            val writer = HistoryStore(harness.db.messageDao(), sessionId = "rs")
            writer.append(Message(Role.USER, "prev user"))
            writer.append(
                Message(Role.ASSISTANT, "prev reply"),
                usage = Usage(promptTokens = 100, outputTokens = 50, totalTokens = 150),
                modelId = "gemini-2.5-flash-lite",
            )
            val fakeApi = FakeLlmApi().apply { queueText("next reply", promptTokens = 200, outputTokens = 20) }
            val store = HistoryStore(harness.db.messageDao(), sessionId = "rs")
            val chat = newChat(prompt = "next", session = "rs")

            // when
            // Phase 2: open a fresh store, run a turn, check that stats
            // reflect prior + new.
            SessionLoop(chat, fakeApi, store, promptSource = stdinSource("/exit\n")).run()

            // then
            assertEquals(2, store.stats.turns)
            assertEquals(300, store.stats.totalPromptTokens) // 100 + 200
            assertEquals(70, store.stats.totalOutputTokens)  // 50 + 20
        }
    }
}
