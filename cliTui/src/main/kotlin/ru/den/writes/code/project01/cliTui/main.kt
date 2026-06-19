package ru.den.writes.code.project01.cliTui

/**
 * Песочница для сравнения TUI-подходов поверх одной заглушки чата
 * ([DemoResponder]). Один бинарь, три режима — выбираются первым аргументом:
 *
 *   cliTui kotter    # declarative TUI без Compose (Varabyte)
 *   cliTui mordant   # богатый вывод + простой цикл ввода (AJ Alt)
 *   cliTui combo     # Kotter-каркас + Mordant-виджеты (footer-Panel)
 *
 * Mosaic (Compose-TUI) выброшен: на macOS-терминале его нативный raw-ввод не
 * перехватывал клавиатуру (символы шли в обычное терминальное эхо). История —
 * в git и в README.
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "kotter" -> runKotterChat()
        "mordant" -> runMordantChat()
        "combo" -> runComboChat()
        "mvp" -> runMyChat()
        "mvi" -> runMviChat(ChatViewModel())
        else -> System.err.println("usage: cliTui <kotter|mordant|combo|mvp|mvi>")
    }
}
