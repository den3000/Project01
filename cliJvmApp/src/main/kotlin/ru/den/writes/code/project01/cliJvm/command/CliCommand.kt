package ru.den.writes.code.project01.cliJvm.command

import ru.den.writes.code.project01.cliJvm.ContextStrategyKind
import ru.den.writes.code.project01.cliJvm.StageAgentSpec
import ru.den.writes.code.project01.cliJvm.StageJudgeSpec
import ru.den.writes.code.project01.shared.llm.ModelProvider
import ru.den.writes.code.project01.shared.memory.MemoryMode

/**
 * The domain layer — WHAT the CLI was asked to do, decoupled from HOW the args
 * were parsed. A [CommandParser] turns `Array<String>` into one of these; a
 * [CommandExecutor] runs it. Both the legacy ([CliArgs]-backed) parser and the
 * CliControls one yield this same set, so execution is agnostic to the front.
 */
internal sealed interface CliCommand {

    /** Print the saved-session list and exit — no LLM, no app runtime. */
    data object ListSessions : CliCommand

    /** Wipe every message / summary / fact row and exit. */
    data object CleanHistory : CliCommand

    /** Duplicate the last [n] rows of session [sessionId] in place (dev stress aid). */
    data class InflateSession(val sessionId: String, val n: Int) : CliCommand

    /** Read or write the on-disk memory files, then exit (no LLM, no session). */
    data class MemoryOp(val action: MemoryAction) : CliCommand

    /** Common shape of the LLM-talking commands — generation knobs + provider. */
    sealed interface RunPrompt : CliCommand {
        val prompt: String
        val maxTokens: Int?
        val stopSequences: List<String>?
        val endSequence: String?
        val temperature: Double?
        val modelProvider: ModelProvider
    }

    /**
     * Interactive chat: send the opening [prompt], then REPL, persisting each
     * turn to [session] (generated id when null). Fields are the chat
     * configuration the runtime needs; semantics mirror the `-flag` docs.
     */
    data class RunChat(
        override val prompt: String,
        override val maxTokens: Int?,
        override val stopSequences: List<String>?,
        override val endSequence: String?,
        override val temperature: Double?,
        override val modelProvider: ModelProvider,
        val session: String?,
        val feedFile: String?,
        val chunkChars: Int,
        val feedInstruction: String,
        val byLine: Boolean,
        val strategy: ContextStrategyKind,
        val keepLast: Int,
        val summarizeEvery: Int,
        val task: String?,
        val profile: String?,
        val memoryMode: MemoryMode?,
        val stageAgents: List<StageAgentSpec>,
        val tui: Boolean,
        val judgeAgents: List<StageJudgeSpec>,
        val mcpServer: String?,
    ) : RunPrompt

    /** Single turn: send [prompt], print the reply, exit. No history / session. */
    data class RunOneShot(
        override val prompt: String,
        override val maxTokens: Int?,
        override val stopSequences: List<String>?,
        override val endSequence: String?,
        override val temperature: Double?,
        override val modelProvider: ModelProvider,
    ) : RunPrompt
}
