package ru.den.writes.code.project01.cliJvm.plain

/** A pre-formatted status line on stderr (resume banner / command result / summary). */
internal data class NoticePlainView(val text: String) : PlainView {
    override fun stderr(): List<String> = listOf(text)
}
