package ru.den.writes.code.agenticHub.platform.filesystem

public actual fun homeDirectory(): String = System.getProperty("user.home")
