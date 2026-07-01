package ru.den.writes.code.project01.cliJvm

import ru.den.writes.code.project01.cliJvm.command.StartCommand
import ru.den.writes.code.project01.cliJvm.db.AppDatabase
import ru.den.writes.code.project01.cliJvm.db.HistoryStore
import ru.den.writes.code.project01.cliJvm.memory.MemoryProvider
import ru.den.writes.code.project01.cliJvm.memory.MemoryStore
import ru.den.writes.code.project01.shared.context.HistoryCompressor

/**
 * Runtime views derived from a parsed [StartCommand.SessionInitialState] — the
 * getters `main` reads to hydrate a session. Each folds the RunChat-vs-RunOneShot
 * split inside (a one-shot has no session/strategy/memory), so callers just ask
 * the state for what they need.
 */

/**
 * The context strategy for this session. RunOneShot (no history) yields
 * [ContextStrategy.FullHistory]; RunChat maps its configured kind to a concrete
 * strategy, wiring the runtime deps.
 */
internal fun StartCommand.SessionInitialState.contextStrategy(): ContextStrategy = when (this) {
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
 */
internal fun StartCommand.SessionInitialState.memoryProvider(): MemoryProvider? = when (this) {
    is StartCommand.RunOneShot -> null
    is StartCommand.RunChat -> config.memoryMode?.let { mode ->
        MEMORY_ROOT.mkdirs()
        MemoryProvider(
            store = MemoryStore(MEMORY_ROOT),
            initialMode = mode,
            initialTaskId = config.task,
            initialProfileName = config.profile,
        )
    }
}

/**
 * The history store for this session, or null for RunOneShot (no persistence).
 * "Resume" = a passed session name AND existing history under it; otherwise it's
 * new and the id is announced so the user can return via `-session`.
 */
internal suspend fun StartCommand.SessionInitialState.historyStore(db: AppDatabase): HistoryStore? = when (this) {
    is StartCommand.RunChat -> {
        val sessionId = config.session ?: generateSessionId()
        val isResume = config.session != null && db.messageDao().all(sessionId).isNotEmpty()
        if (!isResume) {
            System.err.println("[session] new session: $sessionId")
        }
        HistoryStore(db.messageDao(), sessionId)
    }
    is StartCommand.RunOneShot -> null
}
