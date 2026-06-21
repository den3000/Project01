package ru.den.writes.code.project01.cliJvm.agent

import ru.den.writes.code.project01.shared.llm.gemini.GeminiModel
import ru.den.writes.code.project01.shared.llm.ModelProvider
import ru.den.writes.code.project01.cliJvm.CliArgs
import ru.den.writes.code.project01.cliJvm.CommandRunner
import ru.den.writes.code.project01.cliJvm.ContextStrategy
import ru.den.writes.code.project01.cliJvm.ContextStrategyKind
import ru.den.writes.code.project01.cliJvm.PlainView
import ru.den.writes.code.project01.cliJvm.PromptSource
import ru.den.writes.code.project01.cliJvm.PromptSourceIntents
import ru.den.writes.code.project01.cliJvm.RoutedAgent
import ru.den.writes.code.project01.cliJvm.RoutedJudge
import ru.den.writes.code.project01.cliJvm.SessionViewModel
import ru.den.writes.code.project01.cliJvm.StdinPromptSource
import ru.den.writes.code.project01.cliJvm.TurnEngine
import ru.den.writes.code.project01.cliJvm.db.HistoryStore
import ru.den.writes.code.project01.cliJvm.memory.MemoryProvider
import ru.den.writes.code.project01.shared.llm.LlmApi
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.PrintStream
import java.io.StringReader

/**
 * Shared helpers for the Agent-suite tests under
 * [ru.den.writes.code.project01.cliJvm.agent] (and its [agent.branching]
 * sub-package). Lives in its own file because every Agent*Test reuses the
 * same `CliArgs.Chat` factory + dummy provider + stdin source — duplicating
 * the 15-line factory across 8 files would mean 8 places to keep in sync
 * when [CliArgs.Chat] gains a field. `internal` keeps these out of the main
 * code; same compile module → also visible to `agent.branching`.
 */

internal fun newChat(prompt: String, session: String?): CliArgs.Chat = CliArgs.Chat(
    prompt = prompt,
    maxTokens = null,
    stopSequences = null,
    endSequence = null,
    temperature = null,
    modelProvider = dummyGeminiProvider(),
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

/**
 * Agent doesn't dispatch on the provider (the concrete `LlmApi` is already
 * stubbed via `FakeLlmApi`), but `CliArgs.PromptCommand` insists on a
 * non-null [ModelProvider], so tests pass this throwaway.
 */
internal fun dummyGeminiProvider(
    model: GeminiModel = GeminiModel.Default,
): ModelProvider.Gemini = ModelProvider.Gemini(model = model, apiKey = "test-key")

/** Pre-loaded stdin source that hands the REPL the given script line by line. */
internal fun stdinSource(script: String): StdinPromptSource =
    StdinPromptSource(BufferedReader(StringReader(script)))

/**
 * Run a session through the production MVI stack (TurnEngine + SessionViewModel
 * + PlainView) — the test-side equivalent of the old `SessionLoop(...).run()`.
 * Same parameter shape, so migrating a test is a mechanical rename.
 */
internal suspend fun runSessionForTest(
    cliArgs: CliArgs.PromptCommand,
    llmApi: LlmApi,
    historyStore: HistoryStore?,
    promptSource: PromptSource = StdinPromptSource(BufferedReader(InputStreamReader(System.`in`))),
    replAfterFeed: PromptSource? = null,
    strategy: ContextStrategy = ContextStrategy.FullHistory,
    memory: MemoryProvider? = null,
    routedAgents: List<RoutedAgent> = emptyList(),
    routedJudges: List<RoutedJudge> = emptyList(),
) {
    val engine = TurnEngine(cliArgs, llmApi, historyStore, strategy, memory, routedAgents, routedJudges)
    val commandRunner = CommandRunner(historyStore, memory, strategy)
    val viewModel = SessionViewModel(cliArgs, engine, commandRunner, historyStore, memory, strategy)
    val view = PlainView(multiAgent = routedAgents.isNotEmpty())
    view.run(viewModel, PromptSourceIntents(promptSource), replAfterFeed?.let { PromptSourceIntents(it) })
}

/**
 * Process output captured during a run, split by stream. [stdout] carries
 * the reply + per-turn footer; [stderr] carries the `[session]` / `[task]`
 * / `[warning]` / `[error]` / `[branch]` / `[memory]` lines. The split is the
 * contract a user relies on when redirecting a transcript (`> out 2> log`),
 * so the golden tests assert on both halves.
 */
internal data class CapturedOutput(val stdout: String, val stderr: String)

/**
 * Run [block] with `System.out` / `System.err` redirected into buffers and
 * return what each captured (originals restored even on throw). `inline` so
 * the lambda may call `suspend` functions when invoked inside `runTest`.
 * Used by the characterization golden tests that pin the exact output before
 * the MVI refactor relocates rendering into a view.
 */
internal inline fun captureStdoutStderr(block: () -> Unit): CapturedOutput {
    val originalOut = System.out
    val originalErr = System.err
    val outBuf = ByteArrayOutputStream()
    val errBuf = ByteArrayOutputStream()
    System.setOut(PrintStream(outBuf, true, "UTF-8"))
    System.setErr(PrintStream(errBuf, true, "UTF-8"))
    try {
        block()
    } finally {
        System.out.flush()
        System.err.flush()
        System.setOut(originalOut)
        System.setErr(originalErr)
    }
    return CapturedOutput(outBuf.toString("UTF-8"), errBuf.toString("UTF-8"))
}

/**
 * Replace the non-deterministic `duration: <n> ms` footer line with a fixed
 * placeholder so golden comparisons don't flake on per-run timing.
 */
internal fun String.maskDuration(): String =
    replace(Regex("""duration: \d+ ms"""), "duration: <ms> ms")
