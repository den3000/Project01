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

    // --- Day-8 token accounting -------------------------------------

    @Test
    fun `append with usage updates stats and persists token counts`() = runTest {
        TestDb().use { harness ->
            val store = HistoryStore(harness.db.messageDao(), sessionId = "stats")
            store.append(Message(Role.USER, "ask"))
            store.append(
                Message(Role.ASSISTANT, "reply"),
                usage = Usage(
                    promptTokens = 11,
                    outputTokens = 22,
                    thoughtsTokens = 0,
                    totalTokens = 33,
                ),
                modelId = "gemini-2.5-flash-lite",
            )

            assertEquals(1, store.stats.turns)
            assertEquals(11, store.stats.totalPromptTokens)
            assertEquals(22, store.stats.totalOutputTokens)
            assertEquals(33, store.stats.totalTokens)

            // Verify the row really hit the DB with the token columns.
            val rows = harness.db.messageDao().assistantMessages("stats")
            assertEquals(1, rows.size)
            assertEquals(11, rows[0].promptTokens)
            assertEquals(22, rows[0].outputTokens)
            assertEquals(33, rows[0].totalTokens)
            assertEquals("gemini-2.5-flash-lite", rows[0].modelId)
        }
    }

    @Test
    fun `load seeds stats from existing assistant rows`() = runTest {
        TestDb().use { harness ->
            // Seed via one HistoryStore instance.
            val seeder = HistoryStore(harness.db.messageDao(), sessionId = "resume-stats")
            seeder.append(Message(Role.USER, "u1"))
            seeder.append(
                Message(Role.ASSISTANT, "a1"),
                usage = Usage(promptTokens = 100, outputTokens = 200, totalTokens = 300),
                modelId = "gemini-2.5-flash-lite",
            )
            seeder.append(Message(Role.USER, "u2"))
            seeder.append(
                Message(Role.ASSISTANT, "a2"),
                usage = Usage(promptTokens = 400, outputTokens = 50, totalTokens = 450),
                modelId = "gemini-2.5-flash-lite",
            )

            // Fresh store re-opens the same session, load reseeds stats.
            val resumed = HistoryStore(harness.db.messageDao(), sessionId = "resume-stats")
            resumed.load()
            assertEquals(2, resumed.stats.turns)
            assertEquals(500, resumed.stats.totalPromptTokens)
            assertEquals(250, resumed.stats.totalOutputTokens)
            assertEquals(750, resumed.stats.totalTokens)
            // Cost: (100 + 400) * 0.10 + (200 + 50) * 0.40, all over 1e6.
            // = 50 / 1e6 + 100 / 1e6 = 150e-6 = 0.00015
            val expected = (500 * 0.10 + 250 * 0.40) / 1_000_000.0
            assertEquals(expected, resumed.stats.totalCostUsd, 1e-12)
        }
    }

    @Test
    fun `append without usage leaves stats untouched`() = runTest {
        TestDb().use { harness ->
            val store = HistoryStore(harness.db.messageDao(), sessionId = "no-usage")
            store.append(Message(Role.USER, "u"))
            store.append(Message(Role.ASSISTANT, "a"))  // no usage / modelId
            assertEquals(0, store.stats.turns)
            assertEquals(0, store.stats.totalTokens)
        }
    }
}
