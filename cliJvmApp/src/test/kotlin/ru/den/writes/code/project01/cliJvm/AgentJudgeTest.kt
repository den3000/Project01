package ru.den.writes.code.project01.cliJvm

import kotlinx.coroutines.test.runTest
import ru.den.writes.code.project01.cliJvm.agent.runSessionForTest
import ru.den.writes.code.project01.cliJvm.db.HistoryStore
import ru.den.writes.code.project01.cliJvm.memory.MemoryProvider
import ru.den.writes.code.project01.cliJvm.memory.MemoryStore
import ru.den.writes.code.project01.shared.invariant.InvariantChecker
import ru.den.writes.code.project01.shared.invariant.InvariantVerdict
import ru.den.writes.code.project01.shared.invariant.InvariantViolation
import ru.den.writes.code.project01.shared.llm.ModelProvider
import ru.den.writes.code.project01.shared.llm.gemini.GeminiModel
import ru.den.writes.code.project01.shared.memory.MemoryMode
import ru.den.writes.code.project01.shared.memory.TaskBinding
import ru.den.writes.code.project01.shared.memory.TaskNotes
import ru.den.writes.code.project01.shared.memory.TaskStage
import java.io.BufferedReader
import java.io.StringReader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Agent-level behaviour of the per-stage invariant judge: a flagged breach
 * suppresses the turn — the reply is shown but not persisted, and the stage is
 * held. A clean verdict (or a stage no judge spans) leaves the turn untouched.
 */
class AgentJudgeTest {

    @Test
    fun `when judge flags a violation - then reply is not persisted and stage held`() = runTest {
        TestDb().use { harness ->
            withTempMemoryRoot { root ->
                // given — task at clarification; the model would advance, but the judge objects
                val memStore = MemoryStore(root).apply {
                    saveTask(TaskNotes("auth", stage = TaskStage.CLARIFICATION))
                    addRule("Kotlin only, no Spring")
                }
                val memory = MemoryProvider(memStore, MemoryMode.SYSTEM, initialTaskId = "auth")
                val fake = FakeLlmApi().apply { queueText("Use Spring Boot.\n[[stage:planning]]") }
                val store = HistoryStore(harness.db.messageDao(), sessionId = "demo")

                // when
                runSessionForTest(
                    newChat(prompt = "build auth", session = "demo"),
                    fake, store,
                    promptSource = stdinSource("/exit\n"),
                    memory = memory,
                    routedJudges = listOf(violatingJudge),
                )

                // then — stage held, turn dropped from history
                assertEquals(TaskStage.CLARIFICATION, memStore.loadTask("auth")?.stage)
                assertTrue(store.messages.isEmpty(), "violating turn must not be persisted")
            }
        }
    }

    @Test
    fun `when judge passes - then reply persists and stage advances`() = runTest {
        TestDb().use { harness ->
            withTempMemoryRoot { root ->
                // given
                val memStore = MemoryStore(root).apply {
                    saveTask(TaskNotes("auth", stage = TaskStage.CLARIFICATION))
                    addRule("Kotlin only, no Spring")
                }
                val memory = MemoryProvider(memStore, MemoryMode.SYSTEM, initialTaskId = "auth")
                val fake = FakeLlmApi().apply { queueText("Confirmed, Kotlin it is.\n[[stage:planning]]") }
                val store = HistoryStore(harness.db.messageDao(), sessionId = "demo")

                // when
                runSessionForTest(
                    newChat(prompt = "build auth", session = "demo"),
                    fake, store,
                    promptSource = stdinSource("/exit\n"),
                    memory = memory,
                    routedJudges = listOf(cleanJudge),
                )

                // then — clean verdict leaves the turn untouched
                assertEquals(TaskStage.PLANNING, memStore.loadTask("auth")?.stage)
                assertEquals(2, store.messages.size)
            }
        }
    }

    @Test
    fun `when no judge spans the active stage - then the judge is not invoked`() = runTest {
        TestDb().use { harness ->
            withTempMemoryRoot { root ->
                // given — task at execution, but the judge only covers clarification..planning
                val memStore = MemoryStore(root).apply {
                    saveTask(TaskNotes("auth", stage = TaskStage.EXECUTION))
                    addRule("Kotlin only, no Spring")
                }
                val memory = MemoryProvider(memStore, MemoryMode.SYSTEM, initialTaskId = "auth")
                val fake = FakeLlmApi().apply { queueText("Working.\n[[stage:validation]]") }
                val store = HistoryStore(harness.db.messageDao(), sessionId = "demo")
                var calls = 0
                val narrowJudge = RoutedJudge(
                    TaskBinding(TaskStage.CLARIFICATION, TaskStage.PLANNING),
                    InvariantChecker { _, _, _ ->
                        calls++
                        InvariantVerdict(passed = false, violations = listOf(InvariantViolation("001", "x")))
                    },
                    modelId = "test-judge",
                )

                // when
                runSessionForTest(
                    newChat(prompt = "go", session = "demo"),
                    fake, store,
                    promptSource = stdinSource("/exit\n"),
                    memory = memory,
                    routedJudges = listOf(narrowJudge),
                )

                // then — stage uncovered → no judge call, turn proceeds normally
                assertEquals(0, calls)
                assertEquals(TaskStage.VALIDATION, memStore.loadTask("auth")?.stage)
                assertEquals(2, store.messages.size)
            }
        }
    }

    @Test
    fun `when verdict has violations - then invariant lines list them plus the not-saved trailer`() {
        // given — a numbered rule breach and an unnumbered constraint breach
        val verdict = InvariantVerdict(
            passed = false,
            violations = listOf(
                InvariantViolation("001", "proposes Spring"),
                InvariantViolation(null, "off topic"),
            ),
        )

        // when
        val lines = invariantLines(verdict.violations)

        // then — null ruleId renders as "constraint"; the trailer always closes
        assertEquals(
            listOf(
                "[invariant] violated 001: proposes Spring",
                "[invariant] violated constraint: off topic",
                "[invariant] reply not saved to history; task stage held",
            ),
            lines,
        )
    }

    @Test
    fun `when verdict passed - then no invariant lines`() {
        // when - then
        assertTrue(invariantLines(emptyList()).isEmpty())
    }

    //region helpers

    private val violatingJudge = RoutedJudge(
        TaskBinding(TaskStage.CLARIFICATION, TaskStage.DONE),
        InvariantChecker { _, _, _ ->
            InvariantVerdict(passed = false, violations = listOf(InvariantViolation("001", "proposes Spring")))
        },
        modelId = "test-judge",
    )

    private val cleanJudge = RoutedJudge(
        TaskBinding(TaskStage.CLARIFICATION, TaskStage.DONE),
        InvariantChecker { _, _, _ -> InvariantVerdict.CLEAN },
        modelId = "test-judge",
    )

    private fun newChat(prompt: String, session: String?): CliArgs.Chat = CliArgs.Chat(
        prompt = prompt,
        maxTokens = null,
        stopSequences = null,
        endSequence = null,
        temperature = null,
        modelProvider = ModelProvider.Gemini(model = GeminiModel.Default, apiKey = "test-key"),
        session = session,
        feedFile = null,
        chunkChars = 2500,
        feedInstruction = "",
        byLine = false,
        strategy = ContextStrategyKind.FULL,
        keepLast = 6,
        summarizeEvery = 10,
        task = null,
        profile = null,
        memoryMode = null,
        stageAgents = emptyList(),
        tui = false,
        judgeAgents = emptyList(),
    )

    private fun stdinSource(script: String): StdinPromptSource =
        StdinPromptSource(BufferedReader(StringReader(script)))

    private inline fun withTempMemoryRoot(block: (java.io.File) -> Unit) {
        val dir = Files.createTempDirectory("project01-agent-judge-").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }
    //endregion
}
