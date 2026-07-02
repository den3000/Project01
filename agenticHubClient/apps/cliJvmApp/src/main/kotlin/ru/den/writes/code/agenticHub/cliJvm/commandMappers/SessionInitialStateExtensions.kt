package ru.den.writes.code.agenticHub.cliJvm.commandMappers

import ru.den.writes.code.agenticHub.features.lifecycle.command.StartCommand
import ru.den.writes.code.agenticHub.platform.database.AppDatabase
import ru.den.writes.code.agenticHub.features.memory.db.HistoryStore
import ru.den.writes.code.agenticHub.features.memory.db.RoomHistoryStore
import ru.den.writes.code.agenticHub.features.memory.ContextStrategy
import ru.den.writes.code.agenticHub.features.memory.ContextStrategyKind
import ru.den.writes.code.agenticHub.features.memory.StickyFacts
import ru.den.writes.code.agenticHub.features.memory.FileMemoryStore
import ru.den.writes.code.agenticHub.features.memory.MemoryProvider
import ru.den.writes.code.agenticHub.features.memory.HistoryCompressor
import java.util.UUID

/**
 * Hydration of a parsed [StartCommand.SessionInitialState] into the runtime views the
 * composition root (`CliRepl`) needs: history store (Room + stderr announce), context
 * strategy, and memory layer. The app owns "where" (the DB, the memory root path); each
 * getter folds the RunChat-vs-RunOneShot split inside.
 */

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
        RoomHistoryStore(db.messageDao(), sessionId)
    }
    is StartCommand.RunOneShot -> null
}

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
 * [memoryRoot] is the on-disk root the file store writes under (the app owns
 * "where"); [FileMemoryStore] creates it on construction.
 */
internal fun StartCommand.SessionInitialState.memoryProvider(memoryRoot: String): MemoryProvider? = when (this) {
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

/**
 * Eight-char hex slice off a random UUID — readable, easy to retype, ~4 billion
 * values. Matches `^[a-zA-Z0-9_-]+$`, so it's a valid `-session` to resume.
 */
internal fun generateSessionId(): String = UUID.randomUUID().toString().take(8)
