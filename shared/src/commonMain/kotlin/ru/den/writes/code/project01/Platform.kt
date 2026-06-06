package ru.den.writes.code.project01

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform