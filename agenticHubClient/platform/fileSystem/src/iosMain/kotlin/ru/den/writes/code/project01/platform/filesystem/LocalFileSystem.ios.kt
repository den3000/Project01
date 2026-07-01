package ru.den.writes.code.project01.platform.filesystem

public actual fun localFileSystem(): LocalFileSystem =
    // iOS would back this with NSFileManager under NSDocumentDirectory.
    // Wire when the iOS app grows a real local memory store.
    TODO("iOS LocalFileSystem not implemented yet")
