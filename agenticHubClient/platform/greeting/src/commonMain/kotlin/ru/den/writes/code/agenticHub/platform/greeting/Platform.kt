package ru.den.writes.code.agenticHub.platform.greeting

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform