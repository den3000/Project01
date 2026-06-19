package ru.den.writes.code.project01.cliJvm.agent.branching

import kotlinx.coroutines.test.runTest
import ru.den.writes.code.project01.cliJvm.SessionLoop
import ru.den.writes.code.project01.cliJvm.BranchCommand
import ru.den.writes.code.project01.cliJvm.FakeLlmApi
import ru.den.writes.code.project01.cliJvm.PromptResult
import ru.den.writes.code.project01.cliJvm.StdinPromptSource
import ru.den.writes.code.project01.cliJvm.TestDb
import ru.den.writes.code.project01.cliJvm.agent.newChat
import ru.den.writes.code.project01.cliJvm.agent.stdinSource
import ru.den.writes.code.project01.cliJvm.db.HistoryStore
import java.io.BufferedReader
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentBranchingTest {

    //region branch and switch

    @Test
    fun `when slash-branch then slash-switch issued - then history forks and continues on the new branch`() = runTest {
        TestDb().use { harness ->
            // given
            val dao = harness.db.messageDao()
            val fakeApi = FakeLlmApi().apply {
                queueText("r1") // opening turn on main
                queueText("r2") // one turn after switching to alt
            }
            val store = HistoryStore(dao, sessionId = "s")
            val chat = newChat(prompt = "m1", session = "s")

            // when
            SessionLoop(
                cliArgs = chat,
                llmApi = fakeApi,
                historyStore = store,
                promptSource = stdinSource("/branch alt\n/switch alt\na1\n/exit\n"),
            ).run()

            // then
            // Branch commands make no LLM calls — only the two real turns do.
            assertEquals(2, fakeApi.calls.size)
            // main keeps just its opening exchange; alt has the copied prefix + its own turn.
            assertEquals(listOf("m1", "r1"), dao.all("s", "main").map { it.text })
            assertEquals(listOf("m1", "r1", "a1", "r2"), dao.all("s", "alt").map { it.text })
            assertEquals(setOf("main", "alt"), dao.branchesOf("s").toSet())
        }
    }
    //endregion

    //region slash-command classification

    @Test
    fun `when StdinPromptSource reads slash-commands - then each classified to the right PromptResult`() {
        // given
        // Helper to classify a single line through a fresh source.
        fun classify(line: String): PromptResult =
            StdinPromptSource(BufferedReader(StringReader("$line\n"))).nextPrompt()

        // when - then
        // Branch family.
        assertEquals(PromptResult.Command(BranchCommand.Branch("alt")), classify("/branch alt"))
        assertEquals(PromptResult.Command(BranchCommand.Switch("alt")), classify("/switch alt"))
        assertEquals(PromptResult.Command(BranchCommand.ListBranches), classify("/branches"))
        assertEquals(PromptResult.Command(BranchCommand.Checkpoint), classify("/checkpoint"))
        // Non-branch terminators.
        assertEquals(PromptResult.Stop, classify("/exit"))
        // Plain text falls through unchanged.
        assertEquals(PromptResult.Prompt("just a prompt"), classify("just a prompt"))
    }
    //endregion
}
