package ru.den.writes.code.agenticHub.platform.greeting

class Greeting {
    private val platform = getPlatform()

    fun greet(): String {
        return sayHello(platform.name)
    }
}