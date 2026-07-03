package ru.den.writes.code.agenticHub.platform.filesystem.di

import org.koin.core.module.Module
import org.koin.dsl.module
import ru.den.writes.code.agenticHub.platform.filesystem.InMemoryLocalFileSystem
import ru.den.writes.code.agenticHub.platform.filesystem.LocalFileSystem

/**
 * Koin module that binds the platform's [LocalFileSystem].
 *
 * Expressed per target via `actual`: the binding itself can take whatever the
 * platform needs to build an instance (e.g. an Android `Context`) without that
 * leaking into a shared factory signature — the reason this replaces the old
 * `expect fun localFileSystem()`.
 */
internal expect fun fileSystemModule(): Module

/** The platform's file-system Koin module. */
public val fileSystemModule: Module = fileSystemModule()

/**
 * Test counterpart of [fileSystemModule]: binds [LocalFileSystem] to an
 * in-memory fake. A plain `common` module (the fake is platform-agnostic), so
 * — unlike [fileSystemModule], whose android/ios `actual` is still `TODO` — it
 * resolves on **every** target, letting integration graphs run their tests in
 * any platform environment. Compose it in place of [fileSystemModule]; seed and
 * assert through the public [LocalFileSystem] surface. The fake
 * [InMemoryLocalFileSystem] lives next to the real impl.
 *
 * A **function**, not a `val`: each test spins up its own `koinApplication`, and
 * a reused module value would share its `single` instance across them (Koin
 * caches the singleton in the module's factory). A fresh module per call keeps
 * the in-memory state isolated per test. See agenticHubClient/DI.md.
 */
public fun fileSystemTestModule(): Module = module {
    single<LocalFileSystem> { InMemoryLocalFileSystem() }
}
