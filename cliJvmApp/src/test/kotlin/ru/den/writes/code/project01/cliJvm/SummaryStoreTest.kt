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
    fun `when upsertSummary then getSummary called - then all fields round-trip`() = runTest {
        TestDb().use { harness ->
            // given
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

            // when
            dao.upsertSummary(row)
            val actual = dao.getSummary("s1")

            // then
            val expected = row
            assertEquals(expected, actual)
        }
    }

    @Test
    fun `when getSummary called with unknown session - then null returned`() = runTest {
        TestDb().use { harness ->
            // given
            val dao = harness.db.messageDao()

            // when
            val actual = dao.getSummary("nope")

            // then
            assertNull(actual)
        }
    }

    @Test
    fun `when upsertSummary called twice for same session - then second replaces first`() = runTest {
        TestDb().use { harness ->
            // given
            val dao = harness.db.messageDao()
            dao.upsertSummary(SummaryEntity("s1", "first", coveredCount = 4))

            // when
            dao.upsertSummary(
                SummaryEntity(
                    "s1", "second", coveredCount = 8,
                    promptTokens = 50, outputTokens = 10, totalTokens = 60,
                )
            )
            val actual = dao.getSummary("s1")

            // then
            assertEquals("second", actual?.summaryText)
            assertEquals(8, actual?.coveredCount)
            assertEquals(50, actual?.promptTokens)
        }
    }

    @Test
    fun `when two sessions have summaries - then they stay isolated`() = runTest {
        TestDb().use { harness ->
            // given
            val dao = harness.db.messageDao()
            dao.upsertSummary(SummaryEntity("a", "summary A", coveredCount = 2))
            dao.upsertSummary(SummaryEntity("b", "summary B", coveredCount = 6))

            // when
            val sessionAActual = dao.getSummary("a")?.summaryText
            val sessionBActual = dao.getSummary("b")?.summaryText

            // then
            assertEquals("summary A", sessionAActual)
            assertEquals("summary B", sessionBActual)
        }
    }

    @Test
    fun `when clearAllSummaries called - then table is empty`() = runTest {
        TestDb().use { harness ->
            // given
            val dao = harness.db.messageDao()
            dao.upsertSummary(SummaryEntity("a", "summary A", coveredCount = 2))
            dao.upsertSummary(SummaryEntity("b", "summary B", coveredCount = 6))

            // when
            dao.clearAllSummaries()

            // then
            assertNull(dao.getSummary("a"))
            assertNull(dao.getSummary("b"))
        }
    }

    @Test
    fun `when clearAll and clearAllSummaries both called - then both messages and summaries wiped`() = runTest {
        // Mirrors the -clean handler: wipe both tables so no orphan summary
        // survives a reused session id.
        TestDb().use { harness ->
            // given
            val dao = harness.db.messageDao()
            dao.insert(MessageEntity(sessionId = "a", role = "USER", text = "hi"))
            dao.upsertSummary(SummaryEntity("a", "summary A", coveredCount = 2))

            // when
            dao.clearAll()
            dao.clearAllSummaries()

            // then
            assertEquals(0, dao.count())
            assertNull(dao.getSummary("a"))
        }
    }
}
