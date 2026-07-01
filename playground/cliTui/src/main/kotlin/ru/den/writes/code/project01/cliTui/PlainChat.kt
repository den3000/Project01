package ru.den.writes.code.project01.cliTui

/**
 * Plain-фолбэк поверх того же [ChatViewModel] — без Kotter и Mordant. Доказывает,
 * что ядро UI-agnostic: тот же VM и та же логика, но ввод/вывод — обычные
 * readln/println. Raw-режима нет → нет навигации стрелками: пикер выбирается
 * числом (или Enter — текущий пункт), любой другой ввод закрывает его.
 */
fun runPlainChat(vm: ChatViewModel) {
    println("plain — текст уходит эхо-строкой; /profiles — пикер, /exit — выход")
    renderBottom(vm.state.value)
    while (true) {
        val line = readlnOrNull() ?: break
        val shown = vm.state.value.lines.size
        vm.onIntent(ChatIntent.Submit(line.trim()))
        if (vm.effects.tryReceive().getOrNull() == ChatEffect.Exit) break
        val after = vm.state.value
        after.lines.drop(shown).forEach { println(it.render()) } // только добавленное за ход
        renderBottom(after)
    }
}

private fun renderBottom(s: ChatUiState) {
    val p = s.picker
    if (p != null) {
        println("профили:")
        p.options.forEachIndexed { i, opt -> println("  ${i + 1}. $opt") }
        print("выбор (1..${p.options.size}; Enter — первый; прочее — отмена): ")
    } else {
        print("[lines: ${s.lines.size}] > ")
    }
    System.out.flush() // print без \n не флашится сам — иначе prompt не виден до ввода
}

private fun ChatLine.render(): String = when (this) {
    is ChatLine.Entered -> "you've entered: $text"
    is ChatLine.Selected -> "you've selected: $text"
}
