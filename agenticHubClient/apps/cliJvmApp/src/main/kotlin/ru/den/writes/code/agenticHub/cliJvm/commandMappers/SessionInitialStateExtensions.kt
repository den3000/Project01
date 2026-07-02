package ru.den.writes.code.agenticHub.cliJvm.commandMappers

import org.koin.core.Koin
import org.koin.core.parameter.parametersOf
import ru.den.writes.code.agenticHub.features.lifecycle.command.StartCommand
import ru.den.writes.code.agenticHub.features.memory.db.HistoryStore
import ru.den.writes.code.agenticHub.features.memory.ContextStrategy
import ru.den.writes.code.agenticHub.features.memory.ContextStrategyKind
import ru.den.writes.code.agenticHub.features.memory.StickyFacts
import ru.den.writes.code.agenticHub.features.memory.MemoryProvider
import ru.den.writes.code.agenticHub.features.memory.HistoryCompressor
import ru.den.writes.code.agenticHub.platform.database.DEFAULT_BRANCH
import ru.den.writes.code.agenticHub.platform.database.MessageDao
import java.util.UUID

/**
 * Hydration of a parsed [StartCommand.SessionInitialState] into the runtime views the
 * composition root (`CliRepl`) needs: history store (Room + stderr announce), context
 * strategy, and memory layer. The app owns "where" (the DB, the memory root path); the
 * leaf collaborators come from the Koin graph, while each getter folds the
 * RunChat-vs-RunOneShot split inside.
 */

/**
 * The history store for this session, or null for RunOneShot (no persistence).
 * "Resume" = a passed session name AND existing history under it; otherwise it's
 * new and the id is announced so the user can return via `-session`. The
 * [RoomHistoryStore][ru.den.writes.code.agenticHub.features.memory.db.RoomHistoryStore]
 * (and its [MessageDao]) come from the graph.
 */
internal suspend fun StartCommand.SessionInitialState.resolveHistoryStore(koin: Koin): HistoryStore? = when (this) {
    is StartCommand.RunChat -> {
        val dao = koin.get<MessageDao>()
        val sessionId = config.session ?: generateSessionId()
        val isResume = config.session != null && dao.all(sessionId).isNotEmpty()
        if (!isResume) {
            System.err.println("[session] new session: $sessionId")
        }
        koin.get { parametersOf(sessionId, DEFAULT_BRANCH) }
    }
    is StartCommand.RunOneShot -> null
}

/**
 * The context strategy for this session. RunOneShot (no history) yields
 * [ContextStrategy.FullHistory]; RunChat maps its configured kind to a concrete
 * strategy, wiring the runtime deps. Pure domain construction — no injection.
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
 * [memoryRoot] is the on-disk root the file store writes under (the app owns
 * "where"); the [MemoryProvider] (over a `FileMemoryStore`) comes from the graph.
 */
internal fun StartCommand.SessionInitialState.resolveMemoryProvider(koin: Koin, memoryRoot: String): MemoryProvider? = when (this) {
    is StartCommand.RunOneShot -> null
    is StartCommand.RunChat -> config.memoryMode?.let { mode ->
        koin.get { parametersOf(memoryRoot, mode, config.task, config.profile) }
    }
}

/**
 * Eight-char hex slice off a random UUID — readable, easy to retype, ~4 billion
 * values. Matches `^[a-zA-Z0-9_-]+$`, so it's a valid `-session` to resume.
 */
internal fun generateSessionId(): String = UUID.randomUUID().toString().take(8)
