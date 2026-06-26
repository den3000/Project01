package ru.den.writes.code.project01.cliJvm

import kotlinx.coroutines.test.runTest
import ru.den.writes.code.project01.cliJvm.db.HistoryStore
import ru.den.writes.code.project01.shared.llm.Message
import ru.den.writes.code.project01.shared.llm.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Branch deletion ([HistoryStore.deleteBranch]) — the DB side of `/branch clear`.
 * Kept separate from [HistoryStoreTest], which is at the file-size limit.
 */
class HistoryStoreBranchTest {

    @Test
    fun `when deleteBranch called - then that branch's messages summary and facts go`() = runTest {
        TestDb().use { harness ->
            // given — two branches; the doomed one carries messages + summary + facts
            val dao = harness.db.messageDao()
            val exp = HistoryStore(dao, sessionId = "s", initialBranch = "exp")
            exp.append(Message(Role.USER, "e1"))
            exp.append(Message(Role.ASSISTANT, "e2"))
            exp.saveSummary("rolling", coveredCount = 2, modelId = "m", usage = null)
            exp.saveFacts("""{"k":"v"}""", modelId = "m", usage = null)
            HistoryStore(dao, sessionId = "s", initialBranch = "main").append(Message(Role.USER, "m1"))

            // when
            exp.deleteBranch("exp")

            // then — 'exp' gone end-to-end, 'main' untouched
            val freshExp = HistoryStore(dao, sessionId = "s", initialBranch = "exp")
            freshExp.load()
            assertTrue(freshExp.messages.isEmpty())
            assertNull(freshExp.loadSummary())
            assertNull(freshExp.loadFacts())
            assertEquals(listOf("main"), dao.branchesOf("s"))
        }
    }

    @Test
    fun `when deleteBranch targets an absent branch - then it is a no-op and others survive`() = runTest {
        TestDb().use { harness ->
            // given
            val dao = harness.db.messageDao()
            val main = HistoryStore(dao, sessionId = "s", initialBranch = "main")
            main.append(Message(Role.USER, "m1"))

            // when — delete a branch that was never forked
            main.deleteBranch("ghost")

            // then
            assertEquals(listOf("main"), dao.branchesOf("s"))
            assertEquals(1, dao.countSession("s"))
        }
    }
}
