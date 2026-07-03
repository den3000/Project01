package ru.den.writes.code.agenticHub.platform.database.di

import org.koin.core.module.Module
import org.koin.dsl.module
import ru.den.writes.code.agenticHub.platform.database.FakeMessageDao
import ru.den.writes.code.agenticHub.platform.database.MessageDao

/**
 * Koin module binding the Room [AppDatabase][ru.den.writes.code.agenticHub.platform.database.AppDatabase]
 * and its [MessageDao].
 *
 * `actual` per target: the JVM/iOS builders take a file path, Android takes a
 * `Context` — hidden inside the platform binding instead of a shared signature.
 */
internal expect fun databaseModule(): Module

/** The platform's database Koin module. */
public val databaseModule: Module = databaseModule()

/**
 * Test counterpart of [databaseModule]: binds [MessageDao] to an in-memory
 * [FakeMessageDao]. A plain `common` module → runs on every target, no SQLite.
 * Compose it in place of [databaseModule] for graphs whose DB dependency is the
 * DAO (the history layer / `RoomHistoryStore`).
 *
 * It does **not** bind `AppDatabase` — that's a Room-generated class and can't be
 * hand-faked; modules that take `AppDatabase` directly (`startModule`) aren't
 * covered here (use the real [databaseModule] or `TestDb`).
 *
 * A **function**, not a `val`: a reused module value would share its `single`
 * [FakeMessageDao] across every test's `koinApplication` (Koin caches the
 * singleton in the module's factory); a fresh module per call keeps each test's
 * rows isolated. See agenticHubClient/DI.md.
 */
public fun databaseTestModule(): Module = module {
    single<MessageDao> { FakeMessageDao() }
}
