package ru.den.writes.code.project01.shared.util

/**
 * Platform-neutral hook for transient-warning output — e.g. the
 * `[retry] …` notices the LLM API implementations print when they back
 * off and retry a rate-limited or timed-out request.
 *
 * Kept separate from the plain `println` the request-header dump uses so
 * the old stdout/stderr split is preserved on targets that have a stderr:
 * the JVM and Android actuals route here to `System.err`, while iOS falls
 * back to `println`.
 */
internal expect fun logWarn(message: String)
