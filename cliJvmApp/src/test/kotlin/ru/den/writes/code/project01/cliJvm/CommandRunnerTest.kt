package ru.den.writes.code.project01.cliJvm

import kotlinx.coroutines.test.runTest
import ru.den.writes.code.project01.cliJvm.db.HistoryStore
import ru.den.writes.code.project01.cliJvm.memory.MemoryProvider
import ru.den.writes.code.project01.cliJvm.memory.MemoryStore
import ru.den.writes.code.project01.shared.llm.Message
import ru.den.writes.code.project01.shared.llm.Role
import ru.den.writes.code.project01.shared.memory.MemoryMode
import ru.den.writes.code.project01.shared.memory.TaskStage
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [CommandRunner] returns the status line(s) a `/`-command would print, with
 * the DB / disk side effects applied. The strings are what `SessionLoop` and
 * the view-model both render, so they're pinned directly here.
 */
class CommandRunnerTest {

    @Test
    fun `when a branch command has no persisted session - then it explains one is needed`() = runTest {
        // given
        val runner = CommandRunner(historyStore = null, memory = null, strategy = ContextStrategy.FullHistory)

        // when - then
        assertEquals(
            listOf("[branch] branch commands need a persisted session"),
            runner.run(BranchCommand.Checkpoint),
        )
    }

    @Test
    fun `when forking a new branch - then it reports the fork`() = runTest {
        TestDb().use { harness ->
            // given — two messages on the default branch
            val store = HistoryStore(harness.db.messageDao(), sessionId = "s")
            store.append(Message(Role.USER, "a"))
            store.append(Message(Role.ASSISTANT, "b"))
            val runner = CommandRunner(store, memory = null, strategy = ContextStrategy.FullHistory)

            // when - then
            assertEquals(
                listOf("[branch] forked 'main' → 'exp' (2 message(s) copied); /switch exp to continue on it"),
                runner.run(BranchCommand.Branch("exp")),
            )
        }
    }

    @Test
    fun `when a memory command has no provider - then it explains one is needed`() = runTest {
        // given
        val runner = CommandRunner(historyStore = null, memory = null, strategy = ContextStrategy.FullHistory)

        // when - then
        assertEquals(
            listOf("[memory] memory commands need -memory-mode <preamble|system> at startup"),
            runner.run(BranchCommand.ShowMemory),
        )
    }

    @Test
    fun `when setting the memory mode - then it reports the new mode`() = runTest {
        withTempMemoryRoot { root ->
            // given
            val memory = MemoryProvider(MemoryStore(root), MemoryMode.PREAMBLE)
            val runner = CommandRunner(historyStore = null, memory = memory, strategy = ContextStrategy.FullHistory)

            // when - then
            assertEquals(
                listOf("[memory] mode → system"),
                runner.run(BranchCommand.SetMemoryMode(MemoryMode.SYSTEM)),
            )
        }
    }

    @Test
    fun `when setting a new task - then it reports the active task and initial stage`() = runTest {
        withTempMemoryRoot { root ->
            // given
            val memory = MemoryProvider(MemoryStore(root), MemoryMode.SYSTEM)
            val runner = CommandRunner(historyStore = null, memory = memory, strategy = ContextStrategy.FullHistory)

            // when - then
            assertEquals(
                listOf("[memory] active task → fix (new, stage ${TaskStage.INITIAL.keyword})"),
                runner.run(BranchCommand.SetTask("fix")),
            )
        }
    }

    @Test
    fun `when listing named profiles - then the active one is marked`() = runTest {
        withTempMemoryRoot { root ->
            // given
            val mstore = MemoryStore(root).apply {
                touchNamedProfile("work")
                touchNamedProfile("home")
            }
            val memory = MemoryProvider(mstore, MemoryMode.SYSTEM, initialProfileName = "work")
            val runner = CommandRunner(historyStore = null, memory = memory, strategy = ContextStrategy.FullHistory)

            // when
            val out = runner.run(BranchCommand.ListProfiles)

            // then
            assertEquals("[memory] profiles:", out.first())
            assertTrue(out.contains("  * work"), "active profile should carry the * marker")
            assertTrue(out.contains("    home"), "inactive profile should be indented without a marker")
        }
    }

    private inline fun withTempMemoryRoot(block: (java.io.File) -> Unit) {
        val dir = Files.createTempDirectory("project01-command-runner-").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }
}
