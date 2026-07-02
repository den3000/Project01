package ru.den.writes.code.agenticHub.platform.filesystem

public actual fun localFileSystem(): LocalFileSystem =
    // Android would back this with app-internal storage (Context.filesDir).
    // Wire when the Android app grows a real local memory store.
    TODO("Android LocalFileSystem not implemented yet")
