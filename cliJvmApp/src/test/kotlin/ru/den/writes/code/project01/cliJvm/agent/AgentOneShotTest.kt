package ru.den.writes.code.project01.cliJvm.agent

import ru.den.writes.code.project01.shared.llm.gemini.GeminiModel
import ru.den.writes.code.project01.shared.llm.GenerationParams
import ru.den.writes.code.project01.shared.llm.Message
import ru.den.writes.code.project01.shared.llm.Role
import kotlinx.coroutines.test.runTest
import ru.den.writes.code.project01.cliJvm.CliArgs
import ru.den.writes.code.project01.cliJvm.FakeLlmApi
import ru.den.writes.code.project01.cliJvm.TestDb
import ru.den.writes.code.project01.cliJvm.db.HistoryStore
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentOneShotTest {

    @Test
    fun `when OneShot run - then no history loaded and nothing persisted`() = runTest {
        TestDb().use { harness ->
            // given
            val dao = harness.db.messageDao()
            // Pre-existing rows that OneShot must NOT load nor see.
            val seeded = HistoryStore(dao, sessionId = "ignored")
            seeded.append(Message(Role.USER, "old turn"))
            val priorCount = dao.count()

            val fakeApi = FakeLlmApi().apply { queueText("ok") }
            val oneShot = CliArgs.OneShot(
                prompt = "fire and forget",
                maxTokens = null,
                stopSequences = null,
                endSequence = null,
                temperature = null,
                modelProvider = dummyGeminiProvider(),
            )

            // when
            runSessionForTest(oneShot, fakeApi, historyStore = null, promptSource = stdinSource(""))

            // then
            assertEquals(1, fakeApi.calls.size)
            assertEquals(
                listOf(Message(Role.USER, "fire and forget")),
                fakeApi.calls[0].messages,
            )
            assertEquals(priorCount, dao.count())
        }
    }

    @Test
    fun `when OneShot run with generation params - then params forwarded verbatim to LLM`() = runTest {
        // given
        val fakeApi = FakeLlmApi().apply { queueText("ok") }
        val oneShot = CliArgs.OneShot(
            prompt = "x",
            maxTokens = 42,
            stopSequences = listOf("STOP"),
            endSequence = "[END]",
            temperature = 0.5,
            modelProvider = dummyGeminiProvider(GeminiModel.Known.Gemini25Flash),
        )

        // when
        runSessionForTest(oneShot, fakeApi, historyStore = null, promptSource = stdinSource(""))

        // then
        val expected = GenerationParams(
            maxTokens = 42,
            stopSequences = listOf("STOP"),
            endSequence = "[END]",
            temperature = 0.5,
        )
        assertEquals(expected, fakeApi.calls.single().params)
    }
}
