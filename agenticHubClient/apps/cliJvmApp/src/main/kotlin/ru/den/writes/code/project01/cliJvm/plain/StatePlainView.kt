package ru.den.writes.code.project01.cliJvm.plain

/** A session-state line on stderr (resume banner, `/`-command result, or picker status). */
internal data class StatePlainView(val text: String) : PlainView {
    override fun stderr(): List<String> = listOf(text)
}
