package ru.den.writes.code.project01.cliJvm

import kotlinx.coroutines.test.runTest
import ru.den.writes.code.project01.cliJvm.db.HistoryStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

    // --- Day-9 compression: summary persistence + overhead ----------

    @Test
    fun `saveSummary persists the row and folds overhead into stats without a turn`() = runTest {
        TestDb().use { harness ->
            val store = HistoryStore(harness.db.messageDao(), sessionId = "comp")
            store.saveSummary(
                summaryText = "rolling",
                coveredCount = 10,
                modelId = "gemini-2.5-flash-lite",
                usage = Usage(promptTokens = 1000, outputTokens = 200, totalTokens = 1200),
            )

            // Overhead folded in, but turns stays 0 (not a real exchange).
            assertEquals(0, store.stats.turns)
            assertEquals(1000, store.stats.totalPromptTokens)
            assertEquals(200, store.stats.totalOutputTokens)
            assertEquals(1200, store.stats.totalTokens)
            val expected = (1000 * 0.10 + 200 * 0.40) / 1_000_000.0
            assertEquals(expected, store.stats.totalCostUsd, 1e-12)

            // Row persisted with watermark + cumulative tokens.
            val row = harness.db.messageDao().getSummary("comp")
            assertEquals("rolling", row?.summaryText)
            assertEquals(10, row?.coveredCount)
            assertEquals(1000, row?.promptTokens)
        }
    }

    @Test
    fun `saveSummary with null usage persists the row and leaves stats untouched`() = runTest {
        TestDb().use { harness ->
            val store = HistoryStore(harness.db.messageDao(), sessionId = "comp")
            store.saveSummary("rolling", coveredCount = 4, modelId = "gemini-2.5-flash-lite", usage = null)
            assertEquals(0, store.stats.turns)
            assertEquals(0, store.stats.totalTokens)
            assertEquals(0.0, store.stats.totalCostUsd)
            assertEquals("rolling", harness.db.messageDao().getSummary("comp")?.summaryText)
        }
    }

    @Test
    fun `saveSummary accumulates overhead tokens across compactions`() = runTest {
        TestDb().use { harness ->
            val store = HistoryStore(harness.db.messageDao(), sessionId = "comp")
            store.saveSummary(
                "s1", coveredCount = 10, modelId = "gemini-2.5-flash-lite",
                usage = Usage(promptTokens = 100, outputTokens = 20, totalTokens = 120),
            )
            store.saveSummary(
                "s2", coveredCount = 20, modelId = "gemini-2.5-flash-lite",
                usage = Usage(promptTokens = 300, outputTokens = 40, totalTokens = 340),
            )

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
    fun `load re-seeds overhead from the persisted summary row`() = runTest {
        TestDb().use { harness ->
            val w = HistoryStore(harness.db.messageDao(), sessionId = "resume-comp")
            w.saveSummary(
                "rolling", coveredCount = 8, modelId = "gemini-2.5-flash-lite",
                usage = Usage(promptTokens = 500, outputTokens = 100, totalTokens = 600),
            )

            val resumed = HistoryStore(harness.db.messageDao(), sessionId = "resume-comp")
            resumed.load()
            assertEquals(0, resumed.stats.turns)
            assertEquals(500, resumed.stats.totalPromptTokens)
            assertEquals(100, resumed.stats.totalOutputTokens)
            assertEquals(600, resumed.stats.totalTokens)
            val expected = (500 * 0.10 + 100 * 0.40) / 1_000_000.0
            assertEquals(expected, resumed.stats.totalCostUsd, 1e-12)
        }
    }

    @Test
    fun `load combines assistant-row stats with summary overhead`() = runTest {
        TestDb().use { harness ->
            val w = HistoryStore(harness.db.messageDao(), sessionId = "mix")
            w.append(Message(Role.USER, "u"))
            w.append(
                Message(Role.ASSISTANT, "a"),
                usage = Usage(promptTokens = 100, outputTokens = 50, totalTokens = 150),
                modelId = "gemini-2.5-flash-lite",
            )
            w.saveSummary(
                "rolling", coveredCount = 2, modelId = "gemini-2.5-flash-lite",
                usage = Usage(promptTokens = 1000, outputTokens = 10, totalTokens = 1010),
            )

            val resumed = HistoryStore(harness.db.messageDao(), sessionId = "mix")
            resumed.load()
            assertEquals(1, resumed.stats.turns)                 // one real exchange
            assertEquals(1100, resumed.stats.totalPromptTokens)  // 100 + 1000
            assertEquals(60, resumed.stats.totalOutputTokens)    // 50 + 10
        }
    }

    @Test
    fun `loadSummary round-trips summary text and watermark`() = runTest {
        TestDb().use { harness ->
            val w = HistoryStore(harness.db.messageDao(), sessionId = "rt")
            w.saveSummary("the summary", coveredCount = 12, modelId = "m", usage = null)

            val r = HistoryStore(harness.db.messageDao(), sessionId = "rt")
            val row = r.loadSummary()
            assertEquals("the summary", row?.summaryText)
            assertEquals(12, row?.coveredCount)
        }
    }

    @Test
    fun `load without a summary row seeds no overhead`() = runTest {
        TestDb().use { harness ->
            val w = HistoryStore(harness.db.messageDao(), sessionId = "plain")
            w.append(Message(Role.USER, "u"))
            w.append(
                Message(Role.ASSISTANT, "a"),
                usage = Usage(promptTokens = 10, outputTokens = 5, totalTokens = 15),
                modelId = "gemini-2.5-flash-lite",
            )

            val r = HistoryStore(harness.db.messageDao(), sessionId = "plain")
            r.load()
            assertEquals(1, r.stats.turns)
            assertEquals(10, r.stats.totalPromptTokens)
            assertEquals(5, r.stats.totalOutputTokens)
            assertEquals(15, r.stats.totalTokens)
            assertNull(r.loadSummary())
        }
    }

    // --- Day-10 branching + facts -----------------------------------

    @Test
    fun `branches of the same session are isolated`() = runTest {
        TestDb().use { harness ->
            val dao = harness.db.messageDao()
            HistoryStore(dao, sessionId = "s", initialBranch = "main").append(Message(Role.USER, "on main"))
            HistoryStore(dao, sessionId = "s", initialBranch = "feature").append(Message(Role.USER, "on feature"))

            val freshMain = HistoryStore(dao, sessionId = "s", initialBranch = "main")
            freshMain.load()
            assertEquals(listOf(Message(Role.USER, "on main")), freshMain.messages)

            val freshFeature = HistoryStore(dao, sessionId = "s", initialBranch = "feature")
            freshFeature.load()
            assertEquals(listOf(Message(Role.USER, "on feature")), freshFeature.messages)
        }
    }

    @Test
    fun `switchTo re-hydrates the store for the new branch`() = runTest {
        TestDb().use { harness ->
            val dao = harness.db.messageDao()
            HistoryStore(dao, "s", "main").append(Message(Role.USER, "m1"))
            HistoryStore(dao, "s", "feature").append(Message(Role.USER, "f1"))

            val store = HistoryStore(dao, "s", "main")
            store.load()
            assertEquals(listOf(Message(Role.USER, "m1")), store.messages)
            assertEquals("main", store.branchId)

            store.switchTo("feature")
            assertEquals("feature", store.branchId)
            assertEquals(listOf(Message(Role.USER, "f1")), store.messages)
        }
    }

    @Test
    fun `saveFacts folds overhead without a turn and load re-seeds it`() = runTest {
        TestDb().use { harness ->
            val dao = harness.db.messageDao()
            val store = HistoryStore(dao, "s", "main")
            store.saveFacts(
                factsJson = """{"name":"Denis"}""",
                modelId = "gemini-2.5-flash-lite",
                usage = Usage(promptTokens = 300, outputTokens = 20, totalTokens = 320),
            )
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
}
