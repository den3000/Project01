package ru.den.writes.code.project01.cliTui

/**
 * Песочница TUI: один MVI-стек (UI-agnostic [ChatViewModel]) с двумя видами над
 * ним — вид выбирается первым аргументом:
 *
 *   cliTui          # TUI-вид: Kotter+Mordant (declarative)
 *   cliTui plain    # plain-вид: тот же VM, без Kotter/Mordant — фолбэк
 *
 * Полигон для механики либ; боевая интеграция в cliJvmApp — см. INTEGRATION.md.
 */
fun main(args: Array<String>) {
    val vm = ChatViewModel()
    when (args.firstOrNull()) {
        "plain" -> runPlainChat(vm)
        else -> runTuiChat(vm)
    }
}
