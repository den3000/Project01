package ru.den.writes.code.agenticHub.platform.filesystem.di

import org.koin.core.module.Module
import org.koin.dsl.module
import ru.den.writes.code.agenticHub.platform.filesystem.LocalFileSystem
import ru.den.writes.code.agenticHub.platform.filesystem.LocalFileSystemFake

/**
 * Koin module that binds the platform's [LocalFileSystem].
 *
 * Expressed per target via `actual`: the binding itself can take whatever the
 * platform needs to build an instance (e.g. an Android `Context`) without that
 * leaking into a shared factory signature — the reason this replaces the old
 * `expect fun localFileSystem()`.
 */
internal expect fun fileSystemModule(): Module

/**
 * The platform's file-system Koin module. **Eager** top-level `val`: on android/ios
 * the `actual` is still `TODO()`, so initializing this `val` there throws — and
 * Kotlin/Native inits *every* top-level `val` of the file at once, so a test that
 * only touches [fileSystemTestModule] still crashes on iOS. That's an honest signal
 * (the impl isn't iOS-ready), surfaced via `@IgnoreIos` on the affected test rather
 * than hidden by splitting files.
 */
public val fileSystemModule: Module = fileSystemModule()

/**
 * Test counterpart of [fileSystemModule]: binds [LocalFileSystem] to an in-memory
 * [LocalFileSystemFake] (next to the real impl). `factory`, not `single` — every
 * `get()` is a fresh fake, tests independent. Compose it in place of
 * [fileSystemModule]; seed and assert through the public [LocalFileSystem] surface.
 * See agenticHubClient/DI.md.
 */
public val fileSystemTestModule: Module = module {
    factory<LocalFileSystem> { LocalFileSystemFake() }
}
