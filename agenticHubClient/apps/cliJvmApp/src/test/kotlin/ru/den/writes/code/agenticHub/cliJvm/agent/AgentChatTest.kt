package ru.den.writes.code.agenticHub.cliJvm.agent

import ru.den.writes.code.agenticHub.features.llm.di.llmTestModule
import ru.den.writes.code.agenticHub.features.llm.LlmApi
import ru.den.writes.code.agenticHub.features.llm.FakeLlmScript
import org.koin.core.parameter.parametersOf
import ru.den.writes.code.agenticHub.platform.database.MessageDao
import ru.den.writes.code.agenticHub.platform.database.di.databaseTestModule
import org.koin.dsl.koinApplication
import ru.den.writes.code.agenticHub.features.llm.Message
import ru.den.writes.code.agenticHub.features.llm.Role
import kotlinx.coroutines.test.runTest
import ru.den.writes.code.agenticHub.platform.database.TestDb
import ru.den.writes.code.agenticHub.features.memory.db.RoomHistoryStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentChatTest {

    private fun scriptedApi(script: FakeLlmScript): LlmApi =
        koinApplication { modules(llmTestModule) }.koin.get { parametersOf(script) }


    //region opening turn

    @Test
    fun `when Chat with empty history started - then opening prompt sent alone and turn persisted`() = runTest {
        TestDb().use { harness ->
            val koin = koinApplication { modules(databaseTestModule(harness.db)) }.koin
            // given
            val fakeApiScript = FakeLlmScript().apply { queueText("model reply") }
            val fakeApi = scriptedApi(fakeApiScript)
            val store = RoomHistoryStore(koin.get<MessageDao>(), sessionId = "alpha")
            val chat = newChat(prompt = "hi", session = "alpha")

            // when
            runSessionForTest(chat, fakeApi, store, promptSource = createStdinPromptSource("/exit\n"))

            // then
            assertEquals(1, fakeApiScript.calls.size)
            assertEquals(
                listOf(Message(Role.USER, "hi")),
                fakeApiScript.calls[0].messages,
            )
            // Fresh reader sees both rows in the DB.
            val reader = RoomHistoryStore(koin.get<MessageDao>(), sessionId = "alpha")
            reader.load()
            val expected = listOf(
                Message(Role.USER, "hi"),
                Message(Role.ASSISTANT, "model reply"),
            )
            assertEquals(expected, reader.messages)
        }
    }

    @Test
    fun `when Chat with restored history started - then history plus new user turn sent`() = runTest {
        TestDb().use { harness ->
            val koin = koinApplication { modules(databaseTestModule(harness.db)) }.koin
            // given
            val dao = koin.get<MessageDao>()
            val seeder = RoomHistoryStore(dao, sessionId = "alpha")
            seeder.append(Message(Role.USER, "earlier user"))
            seeder.append(Message(Role.ASSISTANT, "earlier assistant"))

            val fakeApiScript = FakeLlmScript().apply { queueText("ok") }

            val fakeApi = scriptedApi(fakeApiScript)
            val store = RoomHistoryStore(dao, sessionId = "alpha")
            val chat = newChat(prompt = "next", session = "alpha")

            // when
            runSessionForTest(chat, fakeApi, store, promptSource = createStdinPromptSource("/exit\n"))

            // then
            val expected = listOf(
                Message(Role.USER, "earlier user"),
                Message(Role.ASSISTANT, "earlier assistant"),
                Message(Role.USER, "next"),
            )
            assertEquals(expected, fakeApiScript.calls.single().messages)
        }
    }

    @Test
    fun `when opening turn fails - then DB left untouched`() = runTest {
        TestDb().use { harness ->
            val koin = koinApplication { modules(databaseTestModule(harness.db)) }.koin
            // given
            val fakeApiScript = FakeLlmScript()
            val fakeApi = scriptedApi(fakeApiScript) // empty queue → returns error
            val store = RoomHistoryStore(koin.get<MessageDao>(), sessionId = "alpha")
            val chat = newChat(prompt = "hi", session = "alpha")

            // when
            runSessionForTest(chat, fakeApi, store, promptSource = createStdinPromptSource("/exit\n"))

            // then
            assertEquals(0, koin.get<MessageDao>().count())
        }
    }

    @Test
    fun `when Chat starts with empty store - then no resumed banner crash`() = runTest {
        // Sanity: empty history → no "resumed" line on stderr. We don't
        // assert on stderr here (would require capturing), but exercise
        // the path to make sure it doesn't crash on an empty store.
        TestDb().use { harness ->
            val koin = koinApplication { modules(databaseTestModule(harness.db)) }.koin
            // given
            val fakeApiScript = FakeLlmScript().apply { queueText("ok") }
            val fakeApi = scriptedApi(fakeApiScript)
            val store = RoomHistoryStore(koin.get<MessageDao>(), sessionId = "fresh")
            val chat = newChat(prompt = "hi", session = "fresh")

            // when
            runSessionForTest(chat, fakeApi, store, promptSource = createStdinPromptSource("/exit\n"))

            // then
            assertTrue(fakeApiScript.calls.isNotEmpty())
        }
    }
    //endregion

    //region slash-reuse

    @Test
    fun `when slash-reuse called after a reply - then last model reply resent as next user turn`() = runTest {
        TestDb().use { harness ->
            val koin = koinApplication { modules(databaseTestModule(harness.db)) }.koin
            // given
            val fakeApiScript = FakeLlmScript().apply {
                queueText("first reply")
                queueText("second reply")
            }
            val fakeApi = scriptedApi(fakeApiScript)
            val store = RoomHistoryStore(koin.get<MessageDao>(), sessionId = "alpha")
            val chat = newChat(prompt = "start", session = "alpha")

            // when
            runSessionForTest(chat, fakeApi, store, promptSource = createStdinPromptSource("/reuse\n/exit\n"))

            // then
            assertEquals(2, fakeApiScript.calls.size)
            // Second call must end with "first reply" as a USER turn —
            // that's what /reuse does: copy last model output to next user.
            assertEquals(
                Message(Role.USER, "first reply"),
                fakeApiScript.calls[1].messages.last(),
            )
        }
    }

    @Test
    fun `when slash-reuse called without prior reply - then no extra LLM call made`() = runTest {
        TestDb().use { harness ->
            val koin = koinApplication { modules(databaseTestModule(harness.db)) }.koin
            // given
            val fakeApiScript = FakeLlmScript()
            val fakeApi = scriptedApi(fakeApiScript) // empty queue → opening fails, no reply to reuse
            val store = RoomHistoryStore(koin.get<MessageDao>(), sessionId = "alpha")
            val chat = newChat(prompt = "start", session = "alpha")

            // when
            runSessionForTest(chat, fakeApi, store, promptSource = createStdinPromptSource("/reuse\n/exit\n"))

            // then
            // Only the failed opening attempt. /reuse silently skipped
            // because StdinPromptSource has no cached reply yet.
            assertEquals(1, fakeApiScript.calls.size)
        }
    }
    //endregion

    //region exit conditions

    @Test
    fun `when stdin EOF reached - then REPL exits cleanly after opening turn`() = runTest {
        TestDb().use { harness ->
            val koin = koinApplication { modules(databaseTestModule(harness.db)) }.koin
            // given
            val fakeApiScript = FakeLlmScript().apply { queueText("ok") }
            val fakeApi = scriptedApi(fakeApiScript)
            val store = RoomHistoryStore(koin.get<MessageDao>(), sessionId = "alpha")
            val chat = newChat(prompt = "hi", session = "alpha")

            // when
            // No /exit — just close the stream immediately after opening.
            runSessionForTest(chat, fakeApi, store, promptSource = createStdinPromptSource(""))

            // then
            // Opening turn went out; REPL didn't try to read anything else.
            assertEquals(1, fakeApiScript.calls.size)
        }
    }
    //endregion
}
