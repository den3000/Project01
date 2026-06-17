package ru.den.writes.code.project01.cliJvm.agent

import ru.den.writes.code.project01.shared.llm.gemini.GeminiModel
import ru.den.writes.code.project01.shared.llm.ModelProvider
import ru.den.writes.code.project01.cliJvm.CliArgs
import ru.den.writes.code.project01.cliJvm.ContextStrategyKind
import ru.den.writes.code.project01.cliJvm.StdinPromptSource
import java.io.BufferedReader
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
