package ru.den.writes.code.agenticHub.cliJvm.plain

/** A transient feed notice on stderr (feed→REPL transition / interim summary). */
internal data class NoticePlainView(val text: String) : PlainView {
    override fun stderr(): List<String> = listOf(text)
}
