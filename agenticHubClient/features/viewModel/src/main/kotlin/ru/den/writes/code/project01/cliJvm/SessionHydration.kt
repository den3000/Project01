package ru.den.writes.code.project01.cliJvm

import ru.den.writes.code.project01.cliJvm.command.StartCommand
import ru.den.writes.code.project01.cliJvm.memory.FileMemoryStore
import ru.den.writes.code.project01.cliJvm.memory.MemoryProvider
import ru.den.writes.code.agenticHub.features.agent.context.HistoryCompressor

/**
 * Runtime views derived from a parsed [StartCommand.SessionInitialState]. Each
 * folds the RunChat-vs-RunOneShot split inside (a one-shot has no strategy/memory),
 * so callers just ask the state for what they need. Portable — the composition
 * root supplies platform bits (e.g. the memory root path).
 */

/**
 * The context strategy for this session. RunOneShot (no history) yields
 * [ContextStrategy.FullHistory]; RunChat maps its configured kind to a concrete
 * strategy, wiring the runtime deps.
 */
public fun StartCommand.SessionInitialState.contextStrategy(): ContextStrategy = when (this) {
    is StartCommand.RunOneShot -> ContextStrategy.FullHistory
    is StartCommand.RunChat -> when (config.strategy) {
        ContextStrategyKind.FULL -> ContextStrategy.FullHistory
        ContextStrategyKind.WINDOW -> ContextStrategy.SlidingWindow(config.keepLast)
        ContextStrategyKind.FACTS -> StickyFacts(config.keepLast)
        ContextStrategyKind.SUMMARY -> ContextStrategy.Summary(
            HistoryCompressor(keepLast = config.keepLast, summarizeEvery = config.summarizeEvery),
        )
    }
}

/**
 * The memory layer for this session, or null when no memory mode is set (then the
 * wire bytes are byte-identical to a no-memory run). RunOneShot never has memory.
 * [memoryRoot] is the on-disk root the file store writes under (the app owns
 * "where"); [FileMemoryStore] creates it on construction.
 */
public fun StartCommand.SessionInitialState.memoryProvider(memoryRoot: String): MemoryProvider? = when (this) {
    is StartCommand.RunOneShot -> null
    is StartCommand.RunChat -> config.memoryMode?.let { mode ->
        MemoryProvider(
            store = FileMemoryStore(memoryRoot),
            initialMode = mode,
            initialTaskId = config.task,
            initialProfileName = config.profile,
        )
    }
}
