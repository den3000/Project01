package ru.den.writes.code.project01.cliJvm

import kotlinx.coroutines.test.runTest
import ru.den.writes.code.project01.cliJvm.db.FactsEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * DAO-level coverage for the `facts` table (schema v4). Runs against a freshly
 * built v4 DB via [TestDb]; that these pass proves the entity↔schema match
 * Room generates. The v3→v4 migration itself is guarded by [MigrationTest].
 */
class FactsStoreTest {

    @Test
    fun `when upsertFacts then getFacts called - then all fields round-trip`() = runTest {
        TestDb().use { harness ->
            // given
            val dao = harness.db.messageDao()
            val row = FactsEntity(
                sessionId = "s1",
                branchId = "main",
                factsJson = """{"name":"Denis","budget":"50"}""",
                modelId = "gemini-2.5-flash-lite",
                promptTokens = 30,
                outputTokens = 12,
                thoughtsTokens = 0,
                totalTokens = 42,
            )

            // when
            dao.upsertFacts(row)
            val actual = dao.getFacts("s1", "main")

            // then
            val expected = row
            assertEquals(expected, actual)
        }
    }

    @Test
    fun `when getFacts called with unknown session or branch - then null returned`() = runTest {
        TestDb().use { harness ->
            // given
            val dao = harness.db.messageDao()
            dao.upsertFacts(FactsEntity(sessionId = "s1", branchId = "main", factsJson = "{}"))

            // when
            val wrongBranchActual = dao.getFacts("s1", "other")
            val wrongSessionActual = dao.getFacts("nope", "main")

            // then
            assertNull(wrongBranchActual)
            assertNull(wrongSessionActual)
        }
    }

    @Test
    fun `when same session has two branches - then facts isolated per branch`() = runTest {
        TestDb().use { harness ->
            // given
            val dao = harness.db.messageDao()
            dao.upsertFacts(FactsEntity(sessionId = "s", branchId = "main", factsJson = """{"k":"main"}"""))
            dao.upsertFacts(FactsEntity(sessionId = "s", branchId = "alt", factsJson = """{"k":"alt"}"""))

            // when
            val mainActual = dao.getFacts("s", "main")?.factsJson
            val altActual = dao.getFacts("s", "alt")?.factsJson

            // then
            assertEquals("""{"k":"main"}""", mainActual)
            assertEquals("""{"k":"alt"}""", altActual)
        }
    }

    @Test
    fun `when upsertFacts called twice for same key - then second value replaces first`() = runTest {
        TestDb().use { harness ->
            // given
            val dao = harness.db.messageDao()
            dao.upsertFacts(FactsEntity(sessionId = "s", branchId = "main", factsJson = "{}"))

            // when
            dao.upsertFacts(FactsEntity(sessionId = "s", branchId = "main", factsJson = """{"k":"v"}"""))
            val actual = dao.getFacts("s", "main")?.factsJson

            // then
            val expected = """{"k":"v"}"""
            assertEquals(expected, actual)
        }
    }

    @Test
    fun `when clearAllFacts called - then table is empty`() = runTest {
        TestDb().use { harness ->
            // given
            val dao = harness.db.messageDao()
            dao.upsertFacts(FactsEntity(sessionId = "s", branchId = "main", factsJson = "{}"))

            // when
            dao.clearAllFacts()
            val actual = dao.getFacts("s", "main")

            // then
            assertNull(actual)
        }
    }
}
