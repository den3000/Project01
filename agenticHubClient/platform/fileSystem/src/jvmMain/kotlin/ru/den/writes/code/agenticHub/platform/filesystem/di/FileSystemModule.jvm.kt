package ru.den.writes.code.agenticHub.platform.filesystem.di

import org.koin.core.module.Module
import org.koin.dsl.module
import ru.den.writes.code.agenticHub.platform.filesystem.JvmLocalFileSystem
import ru.den.writes.code.agenticHub.platform.filesystem.LocalFileSystem

// Stateless and shareable → single.
internal actual fun fileSystemModule(): Module = module {
    single<LocalFileSystem> { JvmLocalFileSystem() }
}
