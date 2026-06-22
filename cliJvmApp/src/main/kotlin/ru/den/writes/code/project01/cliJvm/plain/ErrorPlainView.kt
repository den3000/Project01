package ru.den.writes.code.project01.cliJvm.plain

/** A failed turn — the `[error] <reason>` line on stderr. */
internal data class ErrorPlainView(val reason: String) : PlainView {
    override fun stderr(): List<String> = listOf("[error] $reason")
}
