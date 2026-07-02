package ru.den.writes.code.agenticHub.cliJvm.plain

/** The final session summary on stderr. (The TUI drops it — its stats panel already shows the totals.) */
internal data class SummaryPlainView(val text: String) : PlainView {
    override fun stderr(): List<String> = listOf(text)
}
