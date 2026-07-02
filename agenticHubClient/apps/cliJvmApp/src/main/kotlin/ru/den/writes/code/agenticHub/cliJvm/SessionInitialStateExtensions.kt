package ru.den.writes.code.agenticHub.cliJvm

import ru.den.writes.code.agenticHub.features.viewmodel.generateSessionId
import ru.den.writes.code.agenticHub.features.lifecycle.command.StartCommand
import ru.den.writes.code.agenticHub.platform.database.AppDatabase
import ru.den.writes.code.agenticHub.features.memory.db.HistoryStore
import ru.den.writes.code.agenticHub.features.memory.db.RoomHistoryStore

/**
 * App-side hydration of a parsed [StartCommand.SessionInitialState]. The portable
 * getters (`contextStrategy` / `memoryProvider`) live in `SessionHydration` in
 * features:viewModel; this one stays here because it wires the Room database and
 * announces a new session id on stderr.
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
