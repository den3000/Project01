package ru.den.writes.code.agenticHub.features.memory.di

import org.koin.core.module.Module
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module
import ru.den.writes.code.agenticHub.features.memory.FileMemoryStore
import ru.den.writes.code.agenticHub.features.memory.MemoryMode
import ru.den.writes.code.agenticHub.features.memory.MemoryProvider
import ru.den.writes.code.agenticHub.features.memory.MemoryStore
import ru.den.writes.code.agenticHub.features.memory.db.HistoryStore
import ru.den.writes.code.agenticHub.features.memory.db.RoomHistoryStore
import ru.den.writes.code.agenticHub.platform.database.MessageDao
import ru.den.writes.code.agenticHub.platform.filesystem.LocalFileSystem

/**
 * Koin module for the memory layer.
 *
 * - [MemoryStore]: `root` is a runtime path; the [LocalFileSystem] port comes
 *   from `fileSystemModule` via the graph.
 * - [MemoryProvider]: mode/task/profile are runtime; the store is resolved by
 *   the same `root`.
 * - [HistoryStore]: `MessageDao` comes from `databaseModule`; session/branch are
 *   runtime.
 */
public val memoryModule: Module = module {
    factory<MemoryStore> { (root: String) ->
        FileMemoryStore(root = root, fs = get<LocalFileSystem>())
    }
    factory { (root: String, mode: MemoryMode, taskId: String?, profileName: String?) ->
        MemoryProvider(
            store = get<MemoryStore> { parametersOf(root) },
            initialMode = mode,
            initialTaskId = taskId,
            initialProfileName = profileName,
        )
    }
    factory<HistoryStore> { (sessionId: String, branch: String) ->
        RoomHistoryStore(dao = get<MessageDao>(), sessionId = sessionId, initialBranch = branch)
    }
}
