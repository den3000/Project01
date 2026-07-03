package ru.den.writes.code.agenticHub.platform.filesystem.di

import org.koin.core.module.Module

/**
 * Koin module that binds the platform's [LocalFileSystem][ru.den.writes.code.agenticHub.platform.filesystem.LocalFileSystem].
 *
 * Expressed per target via `actual`: the binding itself can take whatever the
 * platform needs to build an instance (e.g. an Android `Context`) without that
 * leaking into a shared factory signature — the reason this replaces the old
 * `expect fun localFileSystem()`.
 *
 * The test counterpart `fileSystemTestModule` lives in its own file — see there
 * for why (this `val` is eager + `TODO()` on android/ios).
 */
internal expect fun fileSystemModule(): Module

/** The platform's file-system Koin module. */
public val fileSystemModule: Module = fileSystemModule()
