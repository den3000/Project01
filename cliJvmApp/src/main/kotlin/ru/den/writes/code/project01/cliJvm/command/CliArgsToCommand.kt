package ru.den.writes.code.project01.cliJvm.command

import ru.den.writes.code.project01.cliJvm.CliArgs

/**
 * The "safety" bridge: map a legacy [CliArgs] (the proven parser's output) onto
 * the domain [CliCommand]. A pure field-copy + variant rename — [CliArgs] stays
 * the parse DTO, [CliCommand] is the role the executor runs.
 */
internal fun CliArgs.toCliCommand(): CliCommand = when (this) {
    is CliArgs.ListSessions -> CliCommand.ListSessions
    is CliArgs.Clean -> CliCommand.CleanHistory
    is CliArgs.Inflate -> CliCommand.InflateSession(sessionId, n)
    is CliArgs.Memory -> CliCommand.MemoryOp(action)
    is CliArgs.Chat -> CliCommand.RunChat(
        prompt = prompt,
        maxTokens = maxTokens,
        stopSequences = stopSequences,
        endSequence = endSequence,
        temperature = temperature,
        modelProvider = modelProvider,
        session = session,
        feedFile = feedFile,
        chunkChars = chunkChars,
        feedInstruction = feedInstruction,
        byLine = byLine,
        strategy = strategy,
        keepLast = keepLast,
        summarizeEvery = summarizeEvery,
        task = task,
        profile = profile,
        memoryMode = memoryMode,
        stageAgents = stageAgents,
        tui = tui,
        judgeAgents = judgeAgents,
        mcpServer = mcpServer,
    )
    is CliArgs.OneShot -> CliCommand.RunOneShot(
        prompt = prompt,
        maxTokens = maxTokens,
        stopSequences = stopSequences,
        endSequence = endSequence,
        temperature = temperature,
        modelProvider = modelProvider,
    )
}
