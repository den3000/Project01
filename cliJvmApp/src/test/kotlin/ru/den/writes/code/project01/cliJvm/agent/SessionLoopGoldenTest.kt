package ru.den.writes.code.project01.cliJvm.agent

import kotlinx.coroutines.test.runTest
import ru.den.writes.code.project01.cliJvm.BranchCommand
import ru.den.writes.code.project01.cliJvm.CliArgs
import ru.den.writes.code.project01.cliJvm.FakeLlmApi
import ru.den.writes.code.project01.cliJvm.PromptResult
import ru.den.writes.code.project01.cliJvm.PromptSource
import ru.den.writes.code.project01.cliJvm.RoutedAgent
import ru.den.writes.code.project01.cliJvm.SessionLoop
import ru.den.writes.code.project01.cliJvm.TestDb
import ru.den.writes.code.project01.cliJvm.db.HistoryStore
import ru.den.writes.code.project01.cliJvm.memory.MemoryProvider
import ru.den.writes.code.project01.cliJvm.memory.MemoryStore
import ru.den.writes.code.project01.shared.agent.AgentConfig
import ru.den.writes.code.project01.shared.agent.AgentResponder
import ru.den.writes.code.project01.shared.llm.GenerationParams
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
 * Characterization (golden) tests pinning [SessionLoop]'s exact stdout /
 * stderr on a [FakeLlmApi] session, BEFORE the MVI refactor moves rendering
 * into a view. Every refactor commit that follows must keep these byte-for-
 * byte green — they are the safety net for "PlainView reproduces the output".
 *
 * Two deliberate isolations keep the golden stable and focused on the code
 * that actually moves:
 *  - the prompt source is a banner-free stub ([scripted]); the real
 *    [ru.den.writes.code.project01.cliJvm.StdinPromptSource] help banner is
 *    its own concern (it stays in the intent source after the refactor), so
 *    it's left out of the loop-rendering golden;
 *  - models are off the pricing registry (a `Custom("golden-stub")` id) so
 *    the footer reads `cost=$? (no pricing)` and skips the `context:` line —
 *    output then depends only on the usage numbers fixed here, not on the
 *    pricing table (which legitimately changes). The cost / context formulas
 *    are pinned separately in [ContextFillFormatTest].
 */
class SessionLoopGoldenTest {

    @Test
    fun `when a single chat turn completes - then stdout carries reply plus footer and stderr the summary`() = runTest {
        TestDb().use { harness ->
            // given
            val fake = FakeLlmApi().apply { queueText("model reply") }
            val store = HistoryStore(harness.db.messageDao(), sessionId = "alpha")
            val chat = goldenChat(prompt = "hi", session = "alpha")

            // when
            val out = captureStdoutStderr {
                SessionLoop(chat, fake, store, promptSource = scripted()).run()
            }

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
            val seeder = HistoryStore(dao, sessionId = "alpha")
            seeder.append(Message(Role.USER, "earlier user"))
            seeder.append(
                Message(Role.ASSISTANT, "earlier assistant"),
                usage = Usage(promptTokens = 10, outputTokens = 5, thoughtsTokens = 0, totalTokens = 15),
                modelId = "golden-stub",
            )
            val fake = FakeLlmApi().apply { queueText("model reply") }
            val store = HistoryStore(dao, sessionId = "alpha")
            val chat = goldenChat(prompt = "hi", session = "alpha")

            // when
            val out = captureStdoutStderr {
                SessionLoop(chat, fake, store, promptSource = scripted()).run()
            }

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
            val chat = goldenChat(prompt = "hi", session = "alpha")

            // when
            val out = captureStdoutStderr {
                SessionLoop(chat, fake, store, promptSource = scripted()).run()
            }

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
                val out = captureStdoutStderr {
                    SessionLoop(
                        goldenChat(prompt = "hi", session = "demo"),
                        fallback, store,
                        promptSource = scripted(),
                        memory = memory,
                        routedAgents = listOf(routedPlanner(planner)),
                    ).run()
                }

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
            val chat = goldenChat(prompt = "hi", session = "alpha")

            // when — opening turn (2 messages persisted), then a /branch command
            val out = captureStdoutStderr {
                SessionLoop(
                    chat, fake, store,
                    promptSource = scripted(PromptResult.Command(BranchCommand.Branch("exp"))),
                ).run()
            }

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
            val chat = goldenChat(prompt = "hi", session = "alpha")

            // when — primary source stops immediately, then the follow-up REPL takes over
            val out = captureStdoutStderr {
                SessionLoop(
                    chat, fake, store,
                    promptSource = scripted(),
                    replAfterFeed = scripted(),
                ).run()
            }

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

    /** A `CliArgs.Chat` whose model id is off the pricing registry (stable footer). */
    private fun goldenChat(prompt: String, session: String?): CliArgs.Chat =
        newChat(prompt, session).copy(
            modelProvider = ModelProvider.Gemini(GeminiModel.Custom("golden-stub"), apiKey = "test-key"),
        )

    /** Banner-free prompt source: replays [results] then stops (no help banner on stdout). */
    private fun scripted(vararg results: PromptResult): PromptSource = object : PromptSource {
        private val queue = ArrayDeque(results.toList())
        override fun nextPrompt(): PromptResult = if (queue.isEmpty()) PromptResult.Stop else queue.removeFirst()
    }

    private fun routedPlanner(api: FakeLlmApi): RoutedAgent = RoutedAgent(
        binding = TaskBinding(TaskStage.PLANNING, TaskStage.EXECUTION),
        responder = AgentResponder(AgentConfig(llmApi = api, params = GenerationParams(), profileName = "planner")),
        profileName = "planner",
        modelId = "routed-model",
    )

    private inline fun withTempMemoryRoot(block: (java.io.File) -> Unit) {
        val dir = Files.createTempDirectory("project01-golden-").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    private companion object {
        /** The 72-char `<<<…` footer rule, matched byte-for-byte. */
        val RULE = "<".repeat(72)
    }
    //endregion
}
