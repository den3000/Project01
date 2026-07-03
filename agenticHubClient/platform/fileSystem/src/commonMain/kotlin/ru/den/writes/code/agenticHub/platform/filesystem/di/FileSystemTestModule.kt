package ru.den.writes.code.agenticHub.platform.filesystem.di

import org.koin.core.module.Module
import org.koin.dsl.module
import ru.den.writes.code.agenticHub.platform.filesystem.LocalFileSystem
import ru.den.writes.code.agenticHub.platform.filesystem.LocalFileSystemFake

/**
 * Test counterpart of [fileSystemModule]: binds [LocalFileSystem] to an in-memory
 * fake. A plain `common` module (the fake is platform-agnostic), so — unlike
 * [fileSystemModule], whose android/ios `actual` is still `TODO` — it resolves on
 * **every** target, letting integration graphs run their tests in any platform
 * environment. Compose it in place of [fileSystemModule]; seed and assert through
 * the public [LocalFileSystem] surface. The fake [LocalFileSystemFake] lives next
 * to the real impl.
 *
 * `factory`, not `single`: every `get()` yields a fresh in-memory fake, so tests
 * stay independent (no state carried between them, and the module `val` can be
 * reused across `koinApplication`s without leaking a cached instance). Need a
 * pre-seeded fake shared across a graph? Build it in the test and pass it in.
 *
 * Lives in its **own file** (not `FileSystemModule.kt`): the production
 * [fileSystemModule] is an eager top-level `val` whose android/ios `actual` is
 * `TODO()`, and Kotlin/Native initializes every top-level `val` of a file the
 * moment any of them is touched — so co-locating would make merely resolving this
 * test module throw on iOS. Separate file → separate init. See agenticHubClient/DI.md.
 */
public val fileSystemTestModule: Module = module {
    factory<LocalFileSystem> { LocalFileSystemFake() }
}
