package ru.den.writes.code.agenticHub.platform.database.di

import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import ru.den.writes.code.agenticHub.platform.database.DEFAULT_BRANCH
import ru.den.writes.code.agenticHub.platform.database.FactsEntity
import ru.den.writes.code.agenticHub.platform.database.MessageDao
import ru.den.writes.code.agenticHub.platform.database.MessageEntity
import ru.den.writes.code.agenticHub.platform.database.SummaryEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DatabaseTestModuleTest {

    private fun dao(): MessageDao = koinApplication { modules(databaseTestModule()) }.koin.get()

    private fun msg(session: String, role: String, text: String, branch: String = DEFAULT_BRANCH) =
        MessageEntity(sessionId = session, role = role, text = text, branchId = branch)

    @Test
    fun `when rows inserted - then all returns them in insertion order`() = runTest {
        // given
        val dao = dao()
        dao.insert(msg("s", "USER", "a"))
        dao.insert(msg("s", "ASSISTANT", "b"))
        dao.insert(msg("s", "USER", "c"))

        // when
        val all = dao.all("s")

        // then
        assertEquals(listOf("a", "b", "c"), all.map { it.text })
    }

    @Test
    fun `when tail requested - then only the last n rows come back`() = runTest {
        // given
        val dao = dao()
        repeat(5) { dao.insert(msg("s", "USER", "m$it")) }

        // when
        val tail = dao.tail("s", 2)

        // then
        assertEquals(listOf("m3", "m4"), tail.map { it.text })
    }

    @Test
    fun `when assistant filter - then user rows are excluded`() = runTest {
        // given
        val dao = dao()
        dao.insert(msg("s", "USER", "u"))
        dao.insert(msg("s", "ASSISTANT", "a"))

        // when / then
        assertEquals(listOf("a"), dao.assistantMessages("s").map { it.text })
    }

    @Test
    fun `when branch copied - then rows duplicate under the new branch only`() = runTest {
        // given
        val dao = dao()
        dao.insert(msg("s", "USER", "x"))
        dao.insert(msg("s", "ASSISTANT", "y"))

        // when
        dao.copyBranchMessages("s", DEFAULT_BRANCH, "feature")

        // then
        assertEquals(listOf("x", "y"), dao.all("s", "feature").map { it.text })
        assertEquals(2, dao.all("s", DEFAULT_BRANCH).size)
        assertEquals(listOf(DEFAULT_BRANCH, "feature"), dao.branchesOf("s"))
    }

    @Test
    fun `when multiple sessions - then listSessions groups and counts by first appearance`() = runTest {
        // given
        val dao = dao()
        dao.insert(msg("s1", "USER", "a"))
        dao.insert(msg("s2", "USER", "b"))
        dao.insert(msg("s1", "ASSISTANT", "c"))

        // when
        val sessions = dao.listSessions()

        // then — s1 appeared first; its branch has 2 rows
        assertEquals(listOf("s1", "s2"), sessions.map { it.sessionId })
        assertEquals(2, sessions.first { it.sessionId == "s1" }.count)
        assertEquals(3, dao.count())
        assertEquals(2, dao.countSession("s1"))
    }

    @Test
    fun `when summary and facts upserted - then they round-trip and clear per session`() = runTest {
        // given
        val dao = dao()
        dao.upsertSummary(SummaryEntity(sessionId = "s", summaryText = "sum", coveredCount = 3))
        dao.upsertFacts(FactsEntity(sessionId = "s", branchId = DEFAULT_BRANCH, factsJson = "[]"))

        // when / then
        assertEquals("sum", dao.getSummary("s")?.summaryText)
        assertEquals("[]", dao.getFacts("s", DEFAULT_BRANCH)?.factsJson)

        dao.deleteSessionSummaries("s")
        dao.deleteSessionFacts("s")
        assertNull(dao.getSummary("s"))
        assertNull(dao.getFacts("s", DEFAULT_BRANCH))
    }
}
