package ru.den.writes.code.agenticHub.cliJvm

import kotlinx.coroutines.test.runTest
import ru.den.writes.code.agenticHub.features.viewmodel.SessionCommand
import ru.den.writes.code.agenticHub.features.viewmodel.SchedulerControl
import ru.den.writes.code.agenticHub.features.viewmodel.ScheduleAction
import ru.den.writes.code.agenticHub.features.memory.ContextStrategy
import ru.den.writes.code.agenticHub.features.viewmodel.CommandRunner
import ru.den.writes.code.agenticHub.features.viewmodel.CliTaskHandler
import ru.den.writes.code.agenticHub.features.viewmodel.command.ScheduleSpec
import ru.den.writes.code.agenticHub.features.memory.db.RoomHistoryStore
import ru.den.writes.code.agenticHub.features.memory.MemoryProvider
import ru.den.writes.code.agenticHub.features.memory.FileMemoryStore
import ru.den.writes.code.agenticHub.scheduling.InMemoryScheduleStore
import ru.den.writes.code.agenticHub.scheduling.SchedulerEngine
import ru.den.writes.code.agenticHub.features.llm.Message
import ru.den.writes.code.agenticHub.features.llm.Role
import ru.den.writes.code.agenticHub.features.agent.memory.MemoryMode
import ru.den.writes.code.agenticHub.features.agent.memory.ProfileSection
import ru.den.writes.code.agenticHub.features.agent.memory.TaskNotes
import ru.den.writes.code.agenticHub.features.agent.memory.TaskStage
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [CommandRunner] returns the status line(s) a `/`-command would print, with
 * the DB / disk side effects applied. The strings are what the view-model
 * renders as notices, so they're pinned directly here.
 */
class CommandRunnerTest {

    @Test
    fun `when a branch command has no persisted session - then it explains one is needed`() = runTest {
        // given
        val runner = CommandRunner(historyStore = null, memory = null, strategy = ContextStrategy.FullHistory)

        // when - then
        assertEquals(
            listOf("[branch] branch commands need a persisted session"),
            runner.run(SessionCommand.Checkpoint),
        )
    }

    @Test
    fun `when no scheduler is wired - then schedule explains none is running`() = runTest {
        // given
        val runner = CommandRunner(historyStore = null, memory = null, strategy = ContextStrategy.FullHistory)

        // when - then
        assertEquals(
            listOf("[schedule] no scheduler in this session — launch with -schedule … to enable"),
            runner.run(SessionCommand.Schedule(ScheduleSpec.Collect("current_weather", null, seconds = 30, periodic = true))),
        )
    }

    @Test
    fun `when a scheduler is wired - then schedule adds the task and reports it`() = runTest {
        // given — a live engine + control
        val actions = mutableMapOf<String, ScheduleAction>()
        val engine = SchedulerEngine(InMemoryScheduleStore(), CliTaskHandler(actions, toolExecutor = null), now = { 0L })
        val control = SchedulerControl(engine, actions)
        val runner =
            CommandRunner(historyStore = null, memory = null, strategy = ContextStrategy.FullHistory, scheduler = control)

        // when
        val lines = runner.run(SessionCommand.Schedule(ScheduleSpec.Agent("recap", seconds = 60, periodic = false)))

        // then — the task is registered (one active task) and announced
        assertEquals(1, engine.list().size)
        assertTrue(lines.single().startsWith("[schedule] task '"), "was ${lines.single()}")
        assertTrue(lines.single().endsWith("(agent: recap, after 60s)"), "was ${lines.single()}")
    }

    @Test
    fun `when scheduler tasks are listed then cleared - then schedule reports each step`() = runTest {
        // given — a live engine + control with one task added
        val actions = mutableMapOf<String, ScheduleAction>()
        val engine = SchedulerEngine(InMemoryScheduleStore(), CliTaskHandler(actions, toolExecutor = null), now = { 0L })
        val control = SchedulerControl(engine, actions)
        val runner =
            CommandRunner(historyStore = null, memory = null, strategy = ContextStrategy.FullHistory, scheduler = control)
        val task = control.add(ScheduleSpec.Agent("recap", seconds = 60, periodic = false))

        // when - then — list shows it; clear stops all; list then empty
        assertTrue(runner.run(SessionCommand.ListSchedules).single().contains(task.id))
        assertEquals(
            listOf("[schedule] cancelled 1 task(s) — schedule stopped"),
            runner.run(SessionCommand.ClearSchedules),
        )
        assertEquals(listOf("[schedule] no active tasks"), runner.run(SessionCommand.ListSchedules))
    }

    @Test
    fun `when cancelling an unknown schedule id - then it reports none`() = runTest {
        // given
        val actions = mutableMapOf<String, ScheduleAction>()
        val engine = SchedulerEngine(InMemoryScheduleStore(), CliTaskHandler(actions, toolExecutor = null), now = { 0L })
        val runner = CommandRunner(
            historyStore = null, memory = null, strategy = ContextStrategy.FullHistory,
            scheduler = SchedulerControl(engine, actions),
        )

        // when - then
        assertEquals(listOf("[schedule] no active task 'nope'"), runner.run(SessionCommand.CancelSchedule("nope")))
    }

    @Test
    fun `when forking a new branch - then it reports the fork`() = runTest {
        TestDb().use { harness ->
            // given — two messages on the default branch
            val store = RoomHistoryStore(harness.db.messageDao(), sessionId = "s")
            store.append(Message(Role.USER, "a"))
            store.append(Message(Role.ASSISTANT, "b"))
            val runner = CommandRunner(store, memory = null, strategy = ContextStrategy.FullHistory)

            // when - then
            assertEquals(
                listOf("[branch] forked 'main' → 'exp' (2 message(s) copied); /branch switch exp to continue on it"),
                runner.run(SessionCommand.Branch("exp")),
            )
        }
    }

    @Test
    fun `when clearing a branch by name - then it deletes that branch`() = runTest {
        TestDb().use { harness ->
            // given — fork 'exp' off 'main'; we stay on 'main'
            val store = RoomHistoryStore(harness.db.messageDao(), sessionId = "s")
            store.append(Message(Role.USER, "a"))
            store.fork("exp")
            val runner = CommandRunner(store, memory = null, strategy = ContextStrategy.FullHistory)

            // when - then
            assertEquals(listOf("[branch] deleted 'exp'"), runner.run(SessionCommand.DeleteBranch("exp")))
            assertEquals(listOf("main"), harness.db.messageDao().branchesOf("s"))
        }
    }

    @Test
    fun `when clearing the current branch - then it refuses`() = runTest {
        TestDb().use { harness ->
            // given — on 'main'
            val store = RoomHistoryStore(harness.db.messageDao(), sessionId = "s")
            store.append(Message(Role.USER, "a"))
            val runner = CommandRunner(store, memory = null, strategy = ContextStrategy.FullHistory)

            // when - then
            assertEquals(
                listOf("[branch] can't delete the current branch 'main' — /branch switch <other> first"),
                runner.run(SessionCommand.DeleteBranch("main")),
            )
        }
    }

    @Test
    fun `when clearing a branch that doesn't exist - then it says no such branch`() = runTest {
        TestDb().use { harness ->
            // given
            val store = RoomHistoryStore(harness.db.messageDao(), sessionId = "s")
            store.append(Message(Role.USER, "a"))
            val runner = CommandRunner(store, memory = null, strategy = ContextStrategy.FullHistory)

            // when - then
            assertEquals(
                listOf("[branch] no such branch 'ghost' (use /branch to list)"),
                runner.run(SessionCommand.DeleteBranch("ghost")),
            )
        }
    }

    @Test
    fun `when clearing all branches - then every branch but the current goes`() = runTest {
        TestDb().use { harness ->
            // given — two extra branches off 'main'
            val store = RoomHistoryStore(harness.db.messageDao(), sessionId = "s")
            store.append(Message(Role.USER, "a"))
            store.fork("exp")
            store.fork("wip")
            val runner = CommandRunner(store, memory = null, strategy = ContextStrategy.FullHistory)

            // when - then
            assertEquals(
                listOf("[branch] deleted 2 branch(es) (exp, wip); kept current 'main'"),
                runner.run(SessionCommand.ClearBranches),
            )
            assertEquals(listOf("main"), harness.db.messageDao().branchesOf("s"))
        }
    }

    @Test
    fun `when a memory command has no provider - then it explains one is needed`() = runTest {
        // given
        val runner = CommandRunner(historyStore = null, memory = null, strategy = ContextStrategy.FullHistory)

        // when - then
        assertEquals(
            listOf("[memory] memory commands need a memory mode — start with -agent <name> mode <preamble|system>"),
            runner.run(SessionCommand.ShowMemory),
        )
    }

    @Test
    fun `when setting the memory mode - then it reports the new mode`() = runTest {
        withTempMemoryRoot { root ->
            // given
            val memory = MemoryProvider(FileMemoryStore(root.absolutePath), MemoryMode.PREAMBLE)
            val runner = CommandRunner(historyStore = null, memory = memory, strategy = ContextStrategy.FullHistory)

            // when - then
            assertEquals(
                listOf("[memory] mode → system"),
                runner.run(SessionCommand.SetMemoryMode(MemoryMode.SYSTEM)),
            )
        }
    }

    @Test
    fun `when setting a new task - then it reports the active task and initial stage`() = runTest {
        withTempMemoryRoot { root ->
            // given
            val memory = MemoryProvider(FileMemoryStore(root.absolutePath), MemoryMode.SYSTEM)
            val runner = CommandRunner(historyStore = null, memory = memory, strategy = ContextStrategy.FullHistory)

            // when - then
            assertEquals(
                listOf("[memory] active task → fix (new, stage ${TaskStage.INITIAL.keyword})"),
                runner.run(SessionCommand.SetTask("fix")),
            )
        }
    }

    @Test
    fun `when listing named profiles - then the active one is marked`() = runTest {
        withTempMemoryRoot { root ->
            // given
            val mstore = FileMemoryStore(root.absolutePath).apply {
                touchNamedProfile("work")
                touchNamedProfile("home")
            }
            val memory = MemoryProvider(mstore, MemoryMode.SYSTEM, initialProfileName = "work")
            val runner = CommandRunner(historyStore = null, memory = memory, strategy = ContextStrategy.FullHistory)

            // when
            val out = runner.run(SessionCommand.ListProfiles)

            // then
            assertEquals("[memory] profiles:", out.first())
            assertTrue(out.contains("  * work"), "active profile should carry the * marker")
            assertTrue(out.contains("    home"), "inactive profile should be indented without a marker")
        }
    }

    @Test
    fun `when removing a rule by id - then it reports the removal and then its absence`() = runTest {
        withTempMemoryRoot { root ->
            // given — one rule on disk
            val mstore = FileMemoryStore(root.absolutePath)
            val rule = mstore.addRule("always kotlin")
            val memory = MemoryProvider(mstore, MemoryMode.SYSTEM)
            val runner = CommandRunner(historyStore = null, memory = memory, strategy = ContextStrategy.FullHistory)

            // when - then — first removal succeeds, a second call finds nothing
            assertEquals(listOf("[memory] rule ${rule.id} removed"), runner.run(SessionCommand.RemoveRule(rule.id)))
            assertEquals(listOf("[memory] no rule with id '${rule.id}'"), runner.run(SessionCommand.RemoveRule(rule.id)))
        }
    }

    @Test
    fun `when clearing all rules - then it reports the count`() = runTest {
        withTempMemoryRoot { root ->
            // given
            val mstore = FileMemoryStore(root.absolutePath).apply { addRule("a"); addRule("b") }
            val runner = CommandRunner(historyStore = null, memory = MemoryProvider(mstore, MemoryMode.SYSTEM), strategy = ContextStrategy.FullHistory)

            // when - then
            assertEquals(listOf("[memory] cleared 2 rule(s)"), runner.run(SessionCommand.ClearRules))
            assertEquals(emptyList(), mstore.listRules())
        }
    }

    @Test
    fun `when deleting a task then clearing tasks - then each reports`() = runTest {
        withTempMemoryRoot { root ->
            // given
            val mstore = FileMemoryStore(root.absolutePath).apply { saveTask(TaskNotes("auth")); saveTask(TaskNotes("ui")) }
            val runner = CommandRunner(historyStore = null, memory = MemoryProvider(mstore, MemoryMode.SYSTEM), strategy = ContextStrategy.FullHistory)

            // when - then — delete one by id, second call finds nothing, then clear the rest
            assertEquals(listOf("[memory] task 'auth' deleted"), runner.run(SessionCommand.DeleteTask("auth")))
            assertEquals(listOf("[memory] no task 'auth'"), runner.run(SessionCommand.DeleteTask("auth")))
            assertEquals(listOf("[memory] cleared 1 task(s)"), runner.run(SessionCommand.ClearTasks))
            assertEquals(emptyList(), mstore.listTaskIds())
        }
    }

    @Test
    fun `when clearing all profiles - then named and unnamed are gone`() = runTest {
        withTempMemoryRoot { root ->
            // given
            val mstore = FileMemoryStore(root.absolutePath).apply {
                saveProfile("legacy")
                addNamedProfileItem("work", ProfileSection.STYLE, "кратко")
            }
            val runner = CommandRunner(historyStore = null, memory = MemoryProvider(mstore, MemoryMode.SYSTEM), strategy = ContextStrategy.FullHistory)

            // when - then
            assertEquals(listOf("[memory] all profiles cleared (1 named + unnamed)"), runner.run(SessionCommand.ClearAllProfiles))
            assertEquals(emptyList(), mstore.listProfileNames())
            assertNull(mstore.loadProfileData())
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
