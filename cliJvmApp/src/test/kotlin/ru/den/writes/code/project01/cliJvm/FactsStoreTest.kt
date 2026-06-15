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
    fun `upsert then getFacts round-trips all fields`() = runTest {
        TestDb().use { harness ->
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
            dao.upsertFacts(row)
            assertEquals(row, dao.getFacts("s1", "main"))
        }
    }

    @Test
    fun `getFacts returns null for an unknown session or branch`() = runTest {
        TestDb().use { harness ->
            val dao = harness.db.messageDao()
            dao.upsertFacts(FactsEntity(sessionId = "s1", branchId = "main", factsJson = "{}"))
            assertNull(dao.getFacts("s1", "other"))
            assertNull(dao.getFacts("nope", "main"))
        }
    }

    @Test
    fun `facts are isolated per (session, branch)`() = runTest {
        TestDb().use { harness ->
            val dao = harness.db.messageDao()
            dao.upsertFacts(FactsEntity(sessionId = "s", branchId = "main", factsJson = """{"k":"main"}"""))
            dao.upsertFacts(FactsEntity(sessionId = "s", branchId = "alt", factsJson = """{"k":"alt"}"""))
            assertEquals("""{"k":"main"}""", dao.getFacts("s", "main")?.factsJson)
            assertEquals("""{"k":"alt"}""", dao.getFacts("s", "alt")?.factsJson)
        }
    }

    @Test
    fun `upsert replaces facts for the same (session, branch)`() = runTest {
        TestDb().use { harness ->
            val dao = harness.db.messageDao()
            dao.upsertFacts(FactsEntity(sessionId = "s", branchId = "main", factsJson = "{}"))
            dao.upsertFacts(FactsEntity(sessionId = "s", branchId = "main", factsJson = """{"k":"v"}"""))
            assertEquals("""{"k":"v"}""", dao.getFacts("s", "main")?.factsJson)
        }
    }

    @Test
    fun `clearAllFacts empties the table`() = runTest {
        TestDb().use { harness ->
            val dao = harness.db.messageDao()
            dao.upsertFacts(FactsEntity(sessionId = "s", branchId = "main", factsJson = "{}"))
            dao.clearAllFacts()
            assertNull(dao.getFacts("s", "main"))
        }
    }
}
