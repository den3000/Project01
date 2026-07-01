package ru.den.writes.code.project01.platform.filesystem

public actual fun localFileSystem(): LocalFileSystem =
    // Android would back this with app-internal storage (Context.filesDir).
    // Wire when the Android app grows a real local memory store.
    TODO("Android LocalFileSystem not implemented yet")
