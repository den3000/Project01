package ru.den.writes.code.project01.cliJvm

import kotlinx.coroutines.test.runTest
import ru.den.writes.code.project01.cliJvm.db.MessageEntity
import ru.den.writes.code.project01.cliJvm.db.SummaryEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * DAO-level coverage for the `summaries` table (schema v3). Runs against a
 * freshly built v3 DB via [TestDb]; the fact that these pass proves the
 * entity↔schema match Room generates. (The v2→v3 MIGRATION itself is
 * validated separately by a manual run against a real pre-existing DB —
 * see the plan's verification section.)
 */
class SummaryStoreTest {

    @Test
    fun `upsert then getSummary round-trips all fields`() = runTest {
        TestDb().use { harness ->
            val dao = harness.db.messageDao()
            val row = SummaryEntity(
                sessionId = "s1",
                summaryText = "rolling summary",
                coveredCount = 10,
                modelId = "gemini-2.5-flash-lite",
                promptTokens = 100,
                outputTokens = 40,
                thoughtsTokens = 5,
                totalTokens = 145,
            )
            dao.upsertSummary(row)
            assertEquals(row, dao.getSummary("s1"))
        }
    }

    @Test
    fun `getSummary returns null for unknown session`() = runTest {
        TestDb().use { harness ->
            assertNull(harness.db.messageDao().getSummary("nope"))
        }
    }

    @Test
    fun `upsert replaces the existing summary for the same session`() = runTest {
        TestDb().use { harness ->
            val dao = harness.db.messageDao()
            dao.upsertSummary(SummaryEntity("s1", "first", coveredCount = 4))
            dao.upsertSummary(
                SummaryEntity(
                    "s1", "second", coveredCount = 8,
                    promptTokens = 50, outputTokens = 10, totalTokens = 60,
                )
            )
            val got = dao.getSummary("s1")
            assertEquals("second", got?.summaryText)
            assertEquals(8, got?.coveredCount)
            assertEquals(50, got?.promptTokens)
        }
    }

    @Test
    fun `summaries are isolated per session id`() = runTest {
        TestDb().use { harness ->
            val dao = harness.db.messageDao()
            dao.upsertSummary(SummaryEntity("a", "summary A", coveredCount = 2))
            dao.upsertSummary(SummaryEntity("b", "summary B", coveredCount = 6))
            assertEquals("summary A", dao.getSummary("a")?.summaryText)
            assertEquals("summary B", dao.getSummary("b")?.summaryText)
        }
    }

    @Test
    fun `clearAllSummaries empties the table`() = runTest {
        TestDb().use { harness ->
            val dao = harness.db.messageDao()
            dao.upsertSummary(SummaryEntity("a", "summary A", coveredCount = 2))
            dao.upsertSummary(SummaryEntity("b", "summary B", coveredCount = 6))
            dao.clearAllSummaries()
            assertNull(dao.getSummary("a"))
            assertNull(dao.getSummary("b"))
        }
    }

    @Test
    fun `clean clears summaries alongside messages`() = runTest {
        // Mirrors the -clean handler: wipe both tables so no orphan summary
        // survives a reused session id.
        TestDb().use { harness ->
            val dao = harness.db.messageDao()
            dao.insert(MessageEntity(sessionId = "a", role = "USER", text = "hi"))
            dao.upsertSummary(SummaryEntity("a", "summary A", coveredCount = 2))
            dao.clearAll()
            dao.clearAllSummaries()
            assertEquals(0, dao.count())
            assertNull(dao.getSummary("a"))
        }
    }
}
