package ru.den.writes.code.agenticHub.platform.database.di

import org.koin.core.module.Module
import org.koin.dsl.module
import ru.den.writes.code.agenticHub.platform.database.AppDatabase
import ru.den.writes.code.agenticHub.platform.database.MessageDao

/**
 * Koin module binding the Room [AppDatabase] and its [MessageDao].
 *
 * `actual` per target: the JVM/iOS builders take a file path, Android takes a
 * `Context` — hidden inside the platform binding instead of a shared signature.
 */
internal expect fun databaseModule(): Module

/** The platform's database Koin module. */
public val databaseModule: Module = databaseModule()

/**
 * Test counterpart of [databaseModule] that binds a **real** [AppDatabase] the
 * test already built (e.g. `TestDb`, a temp-file Room DB over the bundled SQLite
 * driver) — no table mocking, real SQL. Takes the DB as an argument because it's
 * a closeable resource the test owns (`TestDb().use { … }` handles cleanup) and
 * because it must be *bound* into the graph to reach nested `get<MessageDao>()`
 * (a resolve-time `parametersOf` wouldn't propagate there). `factory` (every
 * `get()` returns the passed-in [db] — no singletons in tests):
 *
 * ```
 * TestDb().use { harness ->
 *     val koin = koinApplication { modules(databaseTestModule(harness.db), memoryModule) }.koin
 *     // real RoomHistoryStore over harness.db, inspect via harness / the DAO
 * }
 * ```
 * See agenticHubClient/DI.md.
 */
public fun databaseTestModule(db: AppDatabase): Module = module {
    factory<AppDatabase> { db }
    factory<MessageDao> { db.messageDao() }
}
