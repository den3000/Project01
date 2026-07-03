package ru.den.writes.code.agenticHub.platform.filesystem

public actual fun homeDirectory(): String =
    // Android has no user-home; a real store would use an app-specific dir
    // (Context.filesDir). Wire when the Android app grows a real local store.
    TODO("Android homeDirectory not implemented yet")
