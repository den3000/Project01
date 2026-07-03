package ru.den.writes.code.agenticHub.cliJvm.agent

import ru.den.writes.code.agenticHub.platform.database.MessageDao
import ru.den.writes.code.agenticHub.platform.database.di.databaseTestModule
import org.koin.dsl.koinApplication
import ru.den.writes.code.agenticHub.features.llm.gemini.GeminiModel
import ru.den.writes.code.agenticHub.features.llm.GenerationParams
import ru.den.writes.code.agenticHub.features.llm.Message
import ru.den.writes.code.agenticHub.features.llm.Role
import kotlinx.coroutines.test.runTest
import ru.den.writes.code.agenticHub.features.lifecycle.command.StartCommand
import ru.den.writes.code.agenticHub.testing.FakeLlmApi
import ru.den.writes.code.agenticHub.platform.database.TestDb
import ru.den.writes.code.agenticHub.features.memory.db.RoomHistoryStore
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentOneShotTest {

    @Test
    fun `when OneShot run - then no history loaded and nothing persisted`() = runTest {
        TestDb().use { harness ->
            val koin = koinApplication { modules(databaseTestModule(harness.db)) }.koin
            // given
            val dao = koin.get<MessageDao>()
            // Pre-existing rows that OneShot must NOT load nor see.
            val seeded = RoomHistoryStore(dao, sessionId = "ignored")
            seeded.append(Message(Role.USER, "old turn"))
            val priorCount = dao.count()

            val fakeApi = FakeLlmApi().apply { queueText("ok") }
            val oneShot = StartCommand.RunOneShot(
                prompt = "fire and forget",
                maxTokens = null,
                stopSequences = null,
                endSequence = null,
                temperature = null,
                modelProvider = dummyGeminiProvider(),
            )

            // when
            runSessionForTest(oneShot, fakeApi, historyStore = null, promptSource = createStdinPromptSource(""))

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
        val oneShot = StartCommand.RunOneShot(
            prompt = "x",
            maxTokens = 42,
            stopSequences = listOf("STOP"),
            endSequence = "[END]",
            temperature = 0.5,
            modelProvider = dummyGeminiProvider(GeminiModel.Known.Gemini25Flash),
        )

        // when
        runSessionForTest(oneShot, fakeApi, historyStore = null, promptSource = createStdinPromptSource(""))

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
