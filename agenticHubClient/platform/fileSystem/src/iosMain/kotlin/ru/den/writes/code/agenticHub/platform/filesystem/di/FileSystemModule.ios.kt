package ru.den.writes.code.agenticHub.platform.filesystem.di

import org.koin.core.module.Module

internal actual fun fileSystemModule(): Module =
    // iOS would back LocalFileSystem with NSFileManager under NSDocumentDirectory.
    // Wire when the iOS app grows a real local memory store.
    TODO("iOS LocalFileSystem not implemented yet")
