package ru.den.writes.code.agenticHub.platform.logging

actual fun logWarn(message: String) {
    System.err.println(message)
}

actual fun logErr(message: String) {
    System.err.println(message)
}
