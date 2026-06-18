package ru.den.writes.code.project01.cliJvm.agent

import ru.den.writes.code.project01.shared.llm.Message
import ru.den.writes.code.project01.shared.llm.Role
import kotlinx.coroutines.test.runTest
import ru.den.writes.code.project01.cliJvm.SessionLoop
import ru.den.writes.code.project01.cliJvm.FakeLlmApi
import ru.den.writes.code.project01.cliJvm.TestDb
import ru.den.writes.code.project01.cliJvm.db.HistoryStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentChatTest {

    //region opening turn

    @Test
    fun `when Chat with empty history started - then opening prompt sent alone and turn persisted`() = runTest {
        TestDb().use { harness ->
            // given
            val fakeApi = FakeLlmApi().apply { queueText("model reply") }
            val store = HistoryStore(harness.db.messageDao(), sessionId = "alpha")
            val chat = newChat(prompt = "hi", session = "alpha")

            // when
            SessionLoop(chat, fakeApi, store, promptSource = stdinSource("/exit\n")).run()

            // then
            assertEquals(1, fakeApi.calls.size)
            assertEquals(
                listOf(Message(Role.USER, "hi")),
                fakeApi.calls[0].messages,
            )
            // Fresh reader sees both rows in the DB.
            val reader = HistoryStore(harness.db.messageDao(), sessionId = "alpha")
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
            // given
            val dao = harness.db.messageDao()
            val seeder = HistoryStore(dao, sessionId = "alpha")
            seeder.append(Message(Role.USER, "earlier user"))
            seeder.append(Message(Role.ASSISTANT, "earlier assistant"))

            val fakeApi = FakeLlmApi().apply { queueText("ok") }
            val store = HistoryStore(dao, sessionId = "alpha")
            val chat = newChat(prompt = "next", session = "alpha")

            // when
            SessionLoop(chat, fakeApi, store, promptSource = stdinSource("/exit\n")).run()

            // then
            val expected = listOf(
                Message(Role.USER, "earlier user"),
                Message(Role.ASSISTANT, "earlier assistant"),
                Message(Role.USER, "next"),
            )
            assertEquals(expected, fakeApi.calls.single().messages)
        }
    }

    @Test
    fun `when opening turn fails - then DB left untouched`() = runTest {
        TestDb().use { harness ->
            // given
            val fakeApi = FakeLlmApi() // empty queue → returns error
            val store = HistoryStore(harness.db.messageDao(), sessionId = "alpha")
            val chat = newChat(prompt = "hi", session = "alpha")

            // when
            SessionLoop(chat, fakeApi, store, promptSource = stdinSource("/exit\n")).run()

            // then
            assertEquals(0, harness.db.messageDao().count())
        }
    }

    @Test
    fun `when Chat starts with empty store - then no resumed banner crash`() = runTest {
        // Sanity: empty history → no "resumed" line on stderr. We don't
        // assert on stderr here (would require capturing), but exercise
        // the path to make sure it doesn't crash on an empty store.
        TestDb().use { harness ->
            // given
            val fakeApi = FakeLlmApi().apply { queueText("ok") }
            val store = HistoryStore(harness.db.messageDao(), sessionId = "fresh")
            val chat = newChat(prompt = "hi", session = "fresh")

            // when
            SessionLoop(chat, fakeApi, store, promptSource = stdinSource("/exit\n")).run()

            // then
            assertTrue(fakeApi.calls.isNotEmpty())
        }
    }
    //endregion

    //region slash-reuse

    @Test
    fun `when slash-reuse called after a reply - then last model reply resent as next user turn`() = runTest {
        TestDb().use { harness ->
            // given
            val fakeApi = FakeLlmApi().apply {
                queueText("first reply")
                queueText("second reply")
            }
            val store = HistoryStore(harness.db.messageDao(), sessionId = "alpha")
            val chat = newChat(prompt = "start", session = "alpha")

            // when
            SessionLoop(chat, fakeApi, store, promptSource = stdinSource("/reuse\n/exit\n")).run()

            // then
            assertEquals(2, fakeApi.calls.size)
            // Second call must end with "first reply" as a USER turn —
            // that's what /reuse does: copy last model output to next user.
            assertEquals(
                Message(Role.USER, "first reply"),
                fakeApi.calls[1].messages.last(),
            )
        }
    }

    @Test
    fun `when slash-reuse called without prior reply - then no extra LLM call made`() = runTest {
        TestDb().use { harness ->
            // given
            val fakeApi = FakeLlmApi() // empty queue → opening fails, no reply to reuse
            val store = HistoryStore(harness.db.messageDao(), sessionId = "alpha")
            val chat = newChat(prompt = "start", session = "alpha")

            // when
            SessionLoop(chat, fakeApi, store, promptSource = stdinSource("/reuse\n/exit\n")).run()

            // then
            // Only the failed opening attempt. /reuse silently skipped
            // because StdinPromptSource has no cached reply yet.
            assertEquals(1, fakeApi.calls.size)
        }
    }
    //endregion

    //region exit conditions

    @Test
    fun `when stdin EOF reached - then REPL exits cleanly after opening turn`() = runTest {
        TestDb().use { harness ->
            // given
            val fakeApi = FakeLlmApi().apply { queueText("ok") }
            val store = HistoryStore(harness.db.messageDao(), sessionId = "alpha")
            val chat = newChat(prompt = "hi", session = "alpha")

            // when
            // No /exit — just close the stream immediately after opening.
            SessionLoop(chat, fakeApi, store, promptSource = stdinSource("")).run()

            // then
            // Opening turn went out; REPL didn't try to read anything else.
            assertEquals(1, fakeApi.calls.size)
        }
    }
    //endregion
}
