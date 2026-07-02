package ru.den.writes.code.agenticHub.platform.filesystem.di

import org.koin.core.module.Module

internal actual fun fileSystemModule(): Module =
    // Android would back LocalFileSystem with app-internal storage (Context.filesDir),
    // injected here. Wire when the Android app grows a real local memory store.
    TODO("Android LocalFileSystem not implemented yet")
