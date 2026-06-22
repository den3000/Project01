package ru.den.writes.code.project01.cliJvm.plain

/** A session-state line on stderr (resume banner now; profile / task-state changes later). */
internal data class StatePlainView(val text: String) : PlainView {
    override fun stderr(): List<String> = listOf(text)
}
