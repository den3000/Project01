package ru.den.writes.code.project01.cliJvm

import kotlinx.coroutines.test.runTest
import ru.den.writes.code.project01.cliJvm.db.HistoryStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryStoreTest {

    @Test
    fun `load on empty store returns empty list`() = runTest {
        TestDb().use { harness ->
            val store = HistoryStore(harness.db.messageDao(), sessionId = "alpha")
            store.load()
            assertTrue(store.messages.isEmpty())
        }
    }

    @Test
    fun `append reflects in messages view immediately`() = runTest {
        TestDb().use { harness ->
            val store = HistoryStore(harness.db.messageDao(), sessionId = "alpha")
            store.append(Message(Role.USER, "hi"))
            store.append(Message(Role.ASSISTANT, "hello"))
            assertEquals(
                listOf(
                    Message(Role.USER, "hi"),
                    Message(Role.ASSISTANT, "hello"),
                ),
                store.messages,
            )
        }
    }

    @Test
    fun `two sessions are isolated in the same DB`() = runTest {
        TestDb().use { harness ->
            val alpha = HistoryStore(harness.db.messageDao(), sessionId = "alpha")
            val beta = HistoryStore(harness.db.messageDao(), sessionId = "beta")

            alpha.append(Message(Role.USER, "for alpha"))
            beta.append(Message(Role.USER, "for beta"))

            val freshAlpha = HistoryStore(harness.db.messageDao(), sessionId = "alpha")
            freshAlpha.load()
            assertEquals(listOf(Message(Role.USER, "for alpha")), freshAlpha.messages)

            val freshBeta = HistoryStore(harness.db.messageDao(), sessionId = "beta")
            freshBeta.load()
            assertEquals(listOf(Message(Role.USER, "for beta")), freshBeta.messages)
        }
    }

    @Test
    fun `load reads back rows persisted by an earlier store instance`() = runTest {
        TestDb().use { harness ->
            val writer = HistoryStore(harness.db.messageDao(), sessionId = "alpha")
            writer.append(Message(Role.USER, "remember 42"))
            writer.append(Message(Role.ASSISTANT, "got it"))

            val reader = HistoryStore(harness.db.messageDao(), sessionId = "alpha")
            reader.load()
            assertEquals(
                listOf(
                    Message(Role.USER, "remember 42"),
                    Message(Role.ASSISTANT, "got it"),
                ),
                reader.messages,
            )
        }
    }

    @Test
    fun `dao count and clearAll cover the cross-session story`() = runTest {
        TestDb().use { harness ->
            val dao = harness.db.messageDao()
            val foo = HistoryStore(dao, sessionId = "foo")
            val bar = HistoryStore(dao, sessionId = "bar")
            foo.append(Message(Role.USER, "1"))
            foo.append(Message(Role.ASSISTANT, "2"))
            bar.append(Message(Role.USER, "3"))

            assertEquals(3, dao.count())

            dao.clearAll()
            assertEquals(0, dao.count())

            val freshFoo = HistoryStore(dao, sessionId = "foo")
            freshFoo.load()
            assertTrue(freshFoo.messages.isEmpty())
        }
    }

    @Test
    fun `listSessions returns one summary per session with correct counts`() = runTest {
        TestDb().use { harness ->
            val dao = harness.db.messageDao()
            val foo = HistoryStore(dao, sessionId = "foo")
            val bar = HistoryStore(dao, sessionId = "bar")
            foo.append(Message(Role.USER, "f1"))
            foo.append(Message(Role.ASSISTANT, "f2"))
            bar.append(Message(Role.USER, "b1"))

            val summaries = dao.listSessions().associate { it.sessionId to it.count }
            assertEquals(mapOf("foo" to 2, "bar" to 1), summaries)
        }
    }
}
