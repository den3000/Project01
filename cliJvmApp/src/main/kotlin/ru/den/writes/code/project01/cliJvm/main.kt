package ru.den.writes.code.project01.cliJvm

import ru.den.writes.code.project01.Greeting
import ru.den.writes.code.project01.sayHello

fun main(args: Array<String>) {
    println(Greeting().greet())

    if (args.isNotEmpty()) {
        val who: String = java.lang.String.join(" ", *args)
        println(sayHello(who))
    }
}