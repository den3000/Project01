package ru.den.writes.code.project01.cliJvm.agent

import kotlinx.coroutines.test.runTest
import ru.den.writes.code.project01.cliJvm.BranchCommand
import ru.den.writes.code.project01.cliJvm.CliArgs
import ru.den.writes.code.project01.cliJvm.CommandRunner
import ru.den.writes.code.project01.cliJvm.ContextStrategy
import ru.den.writes.code.project01.cliJvm.FakeLlmApi
import ru.den.writes.code.project01.cliJvm.IntentSource
import ru.den.writes.code.project01.cliJvm.PlainView
import ru.den.writes.code.project01.cliJvm.RoutedAgent
import ru.den.writes.code.project01.cliJvm.SessionViewModel
import ru.den.writes.code.project01.cliJvm.TestDb
import ru.den.writes.code.project01.cliJvm.TurnEngine
import ru.den.writes.code.project01.cliJvm.UiIntent
import ru.den.writes.code.project01.cliJvm.db.HistoryStore
import ru.den.writes.code.project01.cliJvm.memory.MemoryProvider
import ru.den.writes.code.project01.cliJvm.memory.MemoryStore
import ru.den.writes.code.project01.shared.agent.AgentConfig
import ru.den.writes.code.project01.shared.agent.AgentResponder
import ru.den.writes.code.project01.shared.llm.GenerationParams
import ru.den.writes.code.project01.shared.llm.LlmApi
import ru.den.writes.code.project01.shared.llm.Message
import ru.den.writes.code.project01.shared.llm.ModelProvider
import ru.den.writes.code.project01.shared.llm.Role
import ru.den.writes.code.project01.shared.llm.Usage
import ru.den.writes.code.project01.shared.llm.gemini.GeminiModel
import ru.den.writes.code.project01.shared.memory.MemoryMode
import ru.den.writes.code.project01.shared.memory.TaskBinding
import ru.den.writes.code.project01.shared.memory.TaskNotes
import ru.den.writes.code.project01.shared.memory.TaskStage
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The byte-for-byte payoff: drives the production stack (SessionViewModel +
 * PlainView) over the core scenarios and pins the exact stdout / stderr. This
 * is the characterization safety net for "PlainView reproduces the previous
 * SessionLoop output" — the split a user relies on when redirecting a transcript.
 */
class PlainViewGoldenTest {

    @Test
    fun `when a single chat turn completes - then stdout carries reply plus footer and stderr the summary`() = runTest {
        TestDb().use { harness ->
            // given
            val fake = FakeLlmApi().apply { queueText("model reply") }
            val store = HistoryStore(harness.db.messageDao(), sessionId = "alpha")

            // when
            val out = runPlain(goldenChat("hi", "alpha"), fake, store, intents())

            // then
            val expectedStdout = buildString {
                appendLine("model reply")
                appendLine(RULE)
                appendLine("duration: <ms> ms")
                appendLine("turn:    prompt=10  output=5  total=15  cost=\$? (no pricing)")
                appendLine("session: turns=1 prompt=10  output=5  total=15  cost=\$? (no pricing)")
                appendLine(RULE)
            }
            assertEquals(expectedStdout, out.stdout.maskDuration())
            assertEquals(
                "[session-summary] turns=1  prompt=10  output=5  total=15  " +
                    "cost=0.00000 USD  (current model has no pricing entry)\n",
                out.stderr,
            )
        }
    }

    @Test
    fun `when the session resumes with prior history - then the resumed banner prints to stderr`() = runTest {
        TestDb().use { harness ->
            // given
            val dao = harness.db.messageDao()
            HistoryStore(dao, sessionId = "alpha").apply {
                append(Message(Role.USER, "earlier user"))
                append(
                    Message(Role.ASSISTANT, "earlier assistant"),
                    usage = Usage(promptTokens = 10, outputTokens = 5, thoughtsTokens = 0, totalTokens = 15),
                    modelId = "golden-stub",
                )
            }
            val fake = FakeLlmApi().apply { queueText("model reply") }

            // when
            val out = runPlain(goldenChat("hi", "alpha"), fake, HistoryStore(dao, sessionId = "alpha"), intents())

            // then
            val expectedStderr = buildString {
                appendLine("[session] resumed: 1 prior turn(s), tokens so far: total=15, cost=\$0.00000")
                appendLine(
                    "[session-summary] turns=2  prompt=20  output=10  total=30  " +
                        "cost=0.00000 USD  (current model has no pricing entry)",
                )
            }
            assertEquals(expectedStderr, out.stderr)
        }
    }

    @Test
    fun `when the turn fails - then stderr carries the error and stdout stays empty`() = runTest {
        TestDb().use { harness ->
            // given
            val fake = FakeLlmApi() // empty queue → synthetic error result
            val store = HistoryStore(harness.db.messageDao(), sessionId = "alpha")

            // when
            val out = runPlain(goldenChat("hi", "alpha"), fake, store, intents())

            // then
            assertEquals("", out.stdout)
            val expectedStderr = buildString {
                appendLine("[error] FakeLlmApi: no scripted response")
                appendLine(
                    "[session-summary] turns=0  prompt=0  output=0  total=0  " +
                        "cost=\$0.00  (current model has no pricing entry)",
                )
            }
            assertEquals(expectedStderr, out.stderr)
        }
    }

    @Test
    fun `when multi-agent routing answers - then stdout is prefixed with the agent tag`() = runTest {
        TestDb().use { harness ->
            withTempMemoryRoot { root ->
                // given
                val memStore = MemoryStore(root).apply { saveTask(TaskNotes("t", stage = TaskStage.PLANNING)) }
                val memory = MemoryProvider(memStore, MemoryMode.SYSTEM, initialTaskId = "t")
                val fallback = FakeLlmApi().apply { queueText("fb") }
                val planner = FakeLlmApi().apply { queueText("the plan") }
                val store = HistoryStore(harness.db.messageDao(), sessionId = "demo")

                // when
                val out = runPlain(
                    goldenChat("hi", "demo"), fallback, store, intents(),
                    memory = memory, routedAgents = listOf(routedPlanner(planner)),
                )

                // then
                val expectedStdout = buildString {
                    appendLine("[[AGENT: planner:routed-model]]")
                    appendLine("the plan")
                    appendLine(RULE)
                    appendLine("duration: <ms> ms")
                    appendLine("turn:    prompt=10  output=5  total=15  cost=\$? (no pricing)")
                    appendLine("session: turns=1 prompt=10  output=5  total=15  cost=\$? (no pricing)")
                    appendLine(RULE)
                }
                assertEquals(expectedStdout, out.stdout.maskDuration())
                val expectedStderr = buildString {
                    appendLine("[task] resuming 't' — stage planning")
                    appendLine(
                        "[session-summary] turns=1  prompt=10  output=5  total=15  " +
                            "cost=0.00000 USD  (current model has no pricing entry)",
                    )
                }
                assertEquals(expectedStderr, out.stderr)
            }
        }
    }

    @Test
    fun `when a branch command runs after a turn - then it reports the fork to stderr`() = runTest {
        TestDb().use { harness ->
            // given
            val fake = FakeLlmApi().apply { queueText("model reply") }
            val store = HistoryStore(harness.db.messageDao(), sessionId = "alpha")

            // when — opening turn (2 messages persisted), then a /branch command
            val out = runPlain(
                goldenChat("hi", "alpha"), fake, store,
                intents(UiIntent.SlashCommand(BranchCommand.Branch("exp"))),
            )

            // then
            val expectedStderr = buildString {
                appendLine("[branch] forked 'main' → 'exp' (2 message(s) copied); /switch exp to continue on it")
                appendLine(
                    "[session-summary] turns=1  prompt=10  output=5  total=15  " +
                        "cost=0.00000 USD  (current model has no pricing entry)",
                )
            }
            assertEquals(expectedStderr, out.stderr)
        }
    }

    @Test
    fun `when a feed hands off to a REPL - then interim and final summaries bracket the handoff`() = runTest {
        TestDb().use { harness ->
            // given
            val fake = FakeLlmApi().apply { queueText("model reply") }
            val store = HistoryStore(harness.db.messageDao(), sessionId = "alpha")

            // when
            val out = runPlain(goldenChat("hi", "alpha"), fake, store, primary = intents(), followUp = intents())

            // then
            val expectedStderr = buildString {
                appendLine(
                    "[feed done — interim summary] turns=1  prompt=10  output=5  total=15  " +
                        "cost=0.00000 USD  (current model has no pricing entry)",
                )
                appendLine("[continuing in REPL — type /exit or /quit to leave]")
                appendLine(
                    "[session-summary] turns=1  prompt=10  output=5  total=15  " +
                        "cost=0.00000 USD  (current model has no pricing entry)",
                )
            }
            assertEquals(expectedStderr, out.stderr)
        }
    }

    //region helpers

    private suspend fun runPlain(
        chat: CliArgs.PromptCommand,
        api: LlmApi,
        store: HistoryStore?,
        primary: IntentSource,
        followUp: IntentSource? = null,
        memory: MemoryProvider? = null,
        routedAgents: List<RoutedAgent> = emptyList(),
    ): CapturedOutput {
        val engine = TurnEngine(chat, api, store, ContextStrategy.FullHistory, memory, routedAgents)
        val runner = CommandRunner(store, memory, ContextStrategy.FullHistory)
        val vm = SessionViewModel(chat, engine, runner, store, memory, ContextStrategy.FullHistory)
        val view = PlainView(multiAgent = routedAgents.isNotEmpty())
        return captureStdoutStderr { view.run(vm, primary, followUp) }
    }

    private fun goldenChat(prompt: String, session: String?): CliArgs.Chat =
        newChat(prompt, session).copy(
            modelProvider = ModelProvider.Gemini(GeminiModel.Custom("golden-stub"), apiKey = "test-key"),
        )

    private fun routedPlanner(api: FakeLlmApi): RoutedAgent = RoutedAgent(
        binding = TaskBinding(TaskStage.PLANNING, TaskStage.EXECUTION),
        responder = AgentResponder(AgentConfig(llmApi = api, params = GenerationParams(), profileName = "planner")),
        profileName = "planner",
        modelId = "routed-model",
    )

    private fun intents(vararg items: UiIntent): IntentSource = object : IntentSource {
        private val queue = ArrayDeque(items.toList())
        override suspend fun next(): UiIntent? = queue.removeFirstOrNull()
    }

    private inline fun withTempMemoryRoot(block: (java.io.File) -> Unit) {
        val dir = Files.createTempDirectory("project01-plainview-golden-").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    private companion object {
        val RULE = "<".repeat(72)
    }
    //endregion
}
