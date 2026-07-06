package ru.den.writes.code.agenticHub.features.lifecycle.command

import ru.den.writes.code.agenticHub.features.llm.ModelProvider

/**
 * The domain layer — WHAT the CLI was asked to do, decoupled from HOW the args
 * were parsed. A [CliArgsToStartCommandMapper] turns `Array<String>` into one of
 * these; a [StartExecutor] runs it. Parsing runs through the [CliArgs] front, so
 * execution stays agnostic to how the args were read.
 */
public sealed interface StartCommand {

    /** Print the saved-session list and exit — no LLM, no app runtime. */
    data object ListSessions : StartCommand

    /** Wipe every message / summary / fact row and exit. */
    data object CleanHistory : StartCommand

    /** Delete one session's history by name (per-session twin of [CleanHistory]). */
    data class CleanSession(val sessionId: String) : StartCommand

    /** Duplicate the last [n] rows of session [sessionId] in place (dev stress aid). */
    data class InflateSession(val sessionId: String, val n: Int) : StartCommand

    /** Read or write the on-disk memory files, then exit (no LLM, no session). */
    data class MemoryOp(val action: MemoryAction) : StartCommand

    /** Index the file at [sourcePath] into a RAG index saved under [name], then exit. */
    data class RagAdd(val name: String, val sourcePath: String) : StartCommand

    /** Common shape of the LLM-talking commands — generation knobs + provider. */
    sealed interface SessionInitialState : StartCommand {
        val prompt: String
        val maxTokens: Int?
        val stopSequences: List<String>?
        val endSequence: String?
        val temperature: Double?
        val modelProvider: ModelProvider
    }

    /**
     * Interactive chat: send the opening [prompt], then REPL, persisting each turn
     * to the session in [config]. Per-turn generation knobs live on [SessionInitialState];
     * the session-lifetime setup the runtime hydrates is the [SessionConfig] payload.
     */
    data class RunChat(
        override val prompt: String,
        override val maxTokens: Int?,
        override val stopSequences: List<String>?,
        override val endSequence: String?,
        override val temperature: Double?,
        override val modelProvider: ModelProvider,
        val config: SessionConfig,
    ) : SessionInitialState

    /** Single turn: send [prompt], print the reply, exit. No history / session. */
    data class RunOneShot(
        override val prompt: String,
        override val maxTokens: Int?,
        override val stopSequences: List<String>?,
        override val endSequence: String?,
        override val temperature: Double?,
        override val modelProvider: ModelProvider,
    ) : SessionInitialState
}
