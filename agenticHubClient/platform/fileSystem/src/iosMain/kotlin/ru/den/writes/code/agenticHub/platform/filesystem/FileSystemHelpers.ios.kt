package ru.den.writes.code.agenticHub.platform.filesystem

public actual fun homeDirectory(): String =
    // iOS would resolve this under NSHomeDirectory()/NSDocumentDirectory. Wire
    // when the iOS app grows a real local memory store.
    TODO("iOS homeDirectory not implemented yet")
