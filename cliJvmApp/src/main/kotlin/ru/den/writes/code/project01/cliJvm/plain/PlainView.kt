package ru.den.writes.code.project01.cliJvm.plain

/**
 * Render dictionary for the plain (non-TUI) transcript: each variant builds its
 * own [stdout] / [stderr] lines and [render] prints them to the right stream.
 * The stdout/stderr split is the contract a user relies on when redirecting a
 * transcript (`> out 2> log`); keeping the builders pure makes that split
 * unit-testable without capturing the process streams. A
 * [ru.den.writes.code.project01.cliJvm.UiLine] maps to one of these via
 * `toPlainView` in `PlainRenderer`.
 */
internal sealed interface PlainView {
    /** Lines for stdout — the reply + footer. */
    fun stdout(): List<String> = emptyList()

    /** Lines for stderr — `[session]` / `[task]` / `[warning]` / `[error]` / `[invariant]` / … */
    fun stderr(): List<String> = emptyList()

    fun render() {
        stdout().forEach { println(it) }
        stderr().forEach { System.err.println(it) }
    }
}
