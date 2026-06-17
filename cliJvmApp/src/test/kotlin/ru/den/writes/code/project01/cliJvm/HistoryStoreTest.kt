package ru.den.writes.code.project01.cliJvm

import kotlinx.coroutines.test.runTest
import ru.den.writes.code.project01.cliJvm.db.HistoryStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoryStoreTest {

    //region basic load and append

    @Test
    fun `when load called on empty store - then messages list is empty`() = runTest {
        TestDb().use { harness ->
            // given
            val store = HistoryStore(harness.db.messageDao(), sessionId = "alpha")

            // when
            store.load()

            // then
            assertTrue(store.messages.isEmpty())
        }
    }

    @Test
    fun `when append called - then messages view updates immediately`() = runTest {
        TestDb().use { harness ->
            // given
            val store = HistoryStore(harness.db.messageDao(), sessionId = "alpha")

            // when
            store.append(Message(Role.USER, "hi"))
            store.append(Message(Role.ASSISTANT, "hello"))

            // then
            val expected = listOf(
                Message(Role.USER, "hi"),
                Message(Role.ASSISTANT, "hello"),
            )
            assertEquals(expected, store.messages)
        }
    }

    @Test
    fun `when two sessions written to same DB - then their messages stay isolated`() = runTest {
        TestDb().use { harness ->
            // given
            val alpha = HistoryStore(harness.db.messageDao(), sessionId = "alpha")
            val beta = HistoryStore(harness.db.messageDao(), sessionId = "beta")
            alpha.append(Message(Role.USER, "for alpha"))
            beta.append(Message(Role.USER, "for beta"))

            // when
            val freshAlpha = HistoryStore(harness.db.messageDao(), sessionId = "alpha")
            freshAlpha.load()
            val freshBeta = HistoryStore(harness.db.messageDao(), sessionId = "beta")
            freshBeta.load()

            // then
            assertEquals(listOf(Message(Role.USER, "for alpha")), freshAlpha.messages)
            assertEquals(listOf(Message(Role.USER, "for beta")), freshBeta.messages)
        }
    }

    @Test
    fun `when load called on fresh instance - then rows persisted earlier are read back`() = runTest {
        TestDb().use { harness ->
            // given
            val writer = HistoryStore(harness.db.messageDao(), sessionId = "alpha")
            writer.append(Message(Role.USER, "remember 42"))
            writer.append(Message(Role.ASSISTANT, "got it"))

            // when
            val reader = HistoryStore(harness.db.messageDao(), sessionId = "alpha")
            reader.load()

            // then
            val expected = listOf(
                Message(Role.USER, "remember 42"),
                Message(Role.ASSISTANT, "got it"),
            )
            assertEquals(expected, reader.messages)
        }
    }

    @Test
    fun `when dao clearAll called - then count drops to zero and all sessions become empty`() = runTest {
        TestDb().use { harness ->
            // given
            val dao = harness.db.messageDao()
            val foo = HistoryStore(dao, sessionId = "foo")
            val bar = HistoryStore(dao, sessionId = "bar")
            foo.append(Message(Role.USER, "1"))
            foo.append(Message(Role.ASSISTANT, "2"))
            bar.append(Message(Role.USER, "3"))
            assertEquals(3, dao.count())

            // when
            dao.clearAll()

            // then
            assertEquals(0, dao.count())
            val freshFoo = HistoryStore(dao, sessionId = "foo")
            freshFoo.load()
            assertTrue(freshFoo.messages.isEmpty())
        }
    }

    @Test
    fun `when listSessions called - then one summary per session with correct counts`() = runTest {
        TestDb().use { harness ->
            // given
            val dao = harness.db.messageDao()
            val foo = HistoryStore(dao, sessionId = "foo")
            val bar = HistoryStore(dao, sessionId = "bar")
            foo.append(Message(Role.USER, "f1"))
            foo.append(Message(Role.ASSISTANT, "f2"))
            bar.append(Message(Role.USER, "b1"))

            // when
            val actual = dao.listSessions().associate { it.sessionId to it.count }

            // then
            val expected = mapOf("foo" to 2, "bar" to 1)
            assertEquals(expected, actual)
        }
    }
    //endregion

    //region token accounting (Day-8)

    @Test
    fun `when append called with usage - then stats updated and token counts persisted`() = runTest {
        TestDb().use { harness ->
            // given
            val store = HistoryStore(harness.db.messageDao(), sessionId = "stats")
            store.append(Message(Role.USER, "ask"))

            // when
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

            // then
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
    fun `when load called - then stats seeded from existing assistant rows`() = runTest {
        TestDb().use { harness ->
            // given
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

            // when
            // Fresh store re-opens the same session, load reseeds stats.
            val resumed = HistoryStore(harness.db.messageDao(), sessionId = "resume-stats")
            resumed.load()

            // then
            assertEquals(2, resumed.stats.turns)
            assertEquals(500, resumed.stats.totalPromptTokens)
            assertEquals(250, resumed.stats.totalOutputTokens)
            assertEquals(750, resumed.stats.totalTokens)
            // Cost: (100 + 400) * 0.10 + (200 + 50) * 0.40, all over 1e6.
            val expectedCost = (500 * 0.10 + 250 * 0.40) / 1_000_000.0
            assertEquals(expectedCost, resumed.stats.totalCostUsd, 1e-12)
        }
    }

    @Test
    fun `when append called without usage - then stats untouched`() = runTest {
        TestDb().use { harness ->
            // given
            val store = HistoryStore(harness.db.messageDao(), sessionId = "no-usage")
            store.append(Message(Role.USER, "u"))

            // when
            store.append(Message(Role.ASSISTANT, "a")) // no usage / modelId

            // then
            assertEquals(0, store.stats.turns)
            assertEquals(0, store.stats.totalTokens)
        }
    }
    //endregion

    //region compression — summary persistence and overhead (Day-9)

    @Test
    fun `when saveSummary called - then row persisted and overhead folded into stats without a turn`() = runTest {
        TestDb().use { harness ->
            // given
            val store = HistoryStore(harness.db.messageDao(), sessionId = "comp")

            // when
            store.saveSummary(
                summaryText = "rolling",
                coveredCount = 10,
                modelId = "gemini-2.5-flash-lite",
                usage = Usage(promptTokens = 1000, outputTokens = 200, totalTokens = 1200),
            )

            // then
            // Overhead folded in, but turns stays 0 (not a real exchange).
            assertEquals(0, store.stats.turns)
            assertEquals(1000, store.stats.totalPromptTokens)
            assertEquals(200, store.stats.totalOutputTokens)
            assertEquals(1200, store.stats.totalTokens)
            val expectedCost = (1000 * 0.10 + 200 * 0.40) / 1_000_000.0
            assertEquals(expectedCost, store.stats.totalCostUsd, 1e-12)

            // Row persisted with watermark + cumulative tokens.
            val row = harness.db.messageDao().getSummary("comp")
            assertEquals("rolling", row?.summaryText)
            assertEquals(10, row?.coveredCount)
            assertEquals(1000, row?.promptTokens)
        }
    }

    @Test
    fun `when saveSummary called with null usage - then row persisted but stats untouched`() = runTest {
        TestDb().use { harness ->
            // given
            val store = HistoryStore(harness.db.messageDao(), sessionId = "comp")

            // when
            store.saveSummary("rolling", coveredCount = 4, modelId = "gemini-2.5-flash-lite", usage = null)

            // then
            assertEquals(0, store.stats.turns)
            assertEquals(0, store.stats.totalTokens)
            assertEquals(0.0, store.stats.totalCostUsd)
            assertEquals("rolling", harness.db.messageDao().getSummary("comp")?.summaryText)
        }
    }

    @Test
    fun `when saveSummary called twice - then overhead tokens accumulate across compactions`() = runTest {
        TestDb().use { harness ->
            // given
            val store = HistoryStore(harness.db.messageDao(), sessionId = "comp")

            // when
            store.saveSummary(
                "s1", coveredCount = 10, modelId = "gemini-2.5-flash-lite",
                usage = Usage(promptTokens = 100, outputTokens = 20, totalTokens = 120),
            )
            store.saveSummary(
                "s2", coveredCount = 20, modelId = "gemini-2.5-flash-lite",
                usage = Usage(promptTokens = 300, outputTokens = 40, totalTokens = 340),
            )

            // then
            // Row holds the cumulative overhead; latest summary + watermark win.
            val row = harness.db.messageDao().getSummary("comp")
            assertEquals("s2", row?.summaryText)
            assertEquals(20, row?.coveredCount)
            assertEquals(400, row?.promptTokens) // 100 + 300
            assertEquals(60, row?.outputTokens)  // 20 + 40
            assertEquals(460, row?.totalTokens)  // 120 + 340
            assertEquals(400, store.stats.totalPromptTokens)
            assertEquals(0, store.stats.turns)
        }
    }

    @Test
    fun `when load called with persisted summary - then overhead re-seeded from row`() = runTest {
        TestDb().use { harness ->
            // given
            val writer = HistoryStore(harness.db.messageDao(), sessionId = "resume-comp")
            writer.saveSummary(
                "rolling", coveredCount = 8, modelId = "gemini-2.5-flash-lite",
                usage = Usage(promptTokens = 500, outputTokens = 100, totalTokens = 600),
            )

            // when
            val resumed = HistoryStore(harness.db.messageDao(), sessionId = "resume-comp")
            resumed.load()

            // then
            assertEquals(0, resumed.stats.turns)
            assertEquals(500, resumed.stats.totalPromptTokens)
            assertEquals(100, resumed.stats.totalOutputTokens)
            assertEquals(600, resumed.stats.totalTokens)
            val expectedCost = (500 * 0.10 + 100 * 0.40) / 1_000_000.0
            assertEquals(expectedCost, resumed.stats.totalCostUsd, 1e-12)
        }
    }

    @Test
    fun `when load called with both messages and summary - then stats combine assistant rows with summary overhead`() = runTest {
        TestDb().use { harness ->
            // given
            val writer = HistoryStore(harness.db.messageDao(), sessionId = "mix")
            writer.append(Message(Role.USER, "u"))
            writer.append(
                Message(Role.ASSISTANT, "a"),
                usage = Usage(promptTokens = 100, outputTokens = 50, totalTokens = 150),
                modelId = "gemini-2.5-flash-lite",
            )
            writer.saveSummary(
                "rolling", coveredCount = 2, modelId = "gemini-2.5-flash-lite",
                usage = Usage(promptTokens = 1000, outputTokens = 10, totalTokens = 1010),
            )

            // when
            val resumed = HistoryStore(harness.db.messageDao(), sessionId = "mix")
            resumed.load()

            // then
            assertEquals(1, resumed.stats.turns)                 // one real exchange
            assertEquals(1100, resumed.stats.totalPromptTokens)  // 100 + 1000
            assertEquals(60, resumed.stats.totalOutputTokens)    // 50 + 10
        }
    }

    @Test
    fun `when loadSummary called after saveSummary - then text and watermark round-trip`() = runTest {
        TestDb().use { harness ->
            // given
            val writer = HistoryStore(harness.db.messageDao(), sessionId = "rt")
            writer.saveSummary("the summary", coveredCount = 12, modelId = "m", usage = null)

            // when
            val reader = HistoryStore(harness.db.messageDao(), sessionId = "rt")
            val row = reader.loadSummary()

            // then
            assertEquals("the summary", row?.summaryText)
            assertEquals(12, row?.coveredCount)
        }
    }

    @Test
    fun `when load called without summary row - then no overhead seeded`() = runTest {
        TestDb().use { harness ->
            // given
            val writer = HistoryStore(harness.db.messageDao(), sessionId = "plain")
            writer.append(Message(Role.USER, "u"))
            writer.append(
                Message(Role.ASSISTANT, "a"),
                usage = Usage(promptTokens = 10, outputTokens = 5, totalTokens = 15),
                modelId = "gemini-2.5-flash-lite",
            )

            // when
            val reader = HistoryStore(harness.db.messageDao(), sessionId = "plain")
            reader.load()

            // then
            assertEquals(1, reader.stats.turns)
            assertEquals(10, reader.stats.totalPromptTokens)
            assertEquals(5, reader.stats.totalOutputTokens)
            assertEquals(15, reader.stats.totalTokens)
            assertNull(reader.loadSummary())
        }
    }
    //endregion

    //region branching and facts (Day-10)

    @Test
    fun `when branches written to same session - then their messages stay isolated`() = runTest {
        TestDb().use { harness ->
            // given
            val dao = harness.db.messageDao()
            HistoryStore(dao, sessionId = "s", initialBranch = "main").append(Message(Role.USER, "on main"))
            HistoryStore(dao, sessionId = "s", initialBranch = "feature").append(Message(Role.USER, "on feature"))

            // when
            val freshMain = HistoryStore(dao, sessionId = "s", initialBranch = "main")
            freshMain.load()
            val freshFeature = HistoryStore(dao, sessionId = "s", initialBranch = "feature")
            freshFeature.load()

            // then
            assertEquals(listOf(Message(Role.USER, "on main")), freshMain.messages)
            assertEquals(listOf(Message(Role.USER, "on feature")), freshFeature.messages)
        }
    }

    @Test
    fun `when switchTo called - then store re-hydrated for the new branch`() = runTest {
        TestDb().use { harness ->
            // given
            val dao = harness.db.messageDao()
            HistoryStore(dao, "s", "main").append(Message(Role.USER, "m1"))
            HistoryStore(dao, "s", "feature").append(Message(Role.USER, "f1"))
            val store = HistoryStore(dao, "s", "main")
            store.load()
            assertEquals(listOf(Message(Role.USER, "m1")), store.messages)
            assertEquals("main", store.branchId)

            // when
            store.switchTo("feature")

            // then
            assertEquals("feature", store.branchId)
            assertEquals(listOf(Message(Role.USER, "f1")), store.messages)
        }
    }

    @Test
    fun `when saveFacts called - then overhead folded without a turn and load re-seeds it`() = runTest {
        TestDb().use { harness ->
            // given
            val dao = harness.db.messageDao()
            val store = HistoryStore(dao, "s", "main")

            // when
            store.saveFacts(
                factsJson = """{"name":"Denis"}""",
                modelId = "gemini-2.5-flash-lite",
                usage = Usage(promptTokens = 300, outputTokens = 20, totalTokens = 320),
            )

            // then
            assertEquals(0, store.stats.turns)
            assertEquals(300, store.stats.totalPromptTokens)

            val resumed = HistoryStore(dao, "s", "main")
            resumed.load()
            assertEquals(0, resumed.stats.turns)
            assertEquals(300, resumed.stats.totalPromptTokens)
            assertEquals("""{"name":"Denis"}""", resumed.loadFacts()?.factsJson)
            // Facts are per-branch: the 'feature' branch has none of its own.
            assertNull(HistoryStore(dao, "s", "feature").loadFacts())
        }
    }
    //endregion
}
