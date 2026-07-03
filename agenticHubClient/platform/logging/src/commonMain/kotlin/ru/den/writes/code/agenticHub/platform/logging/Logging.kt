package ru.den.writes.code.agenticHub.platform.logging

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
expect fun logWarn(message: String)

/**
 * Platform-neutral hook for error-stream output — the stderr half of a CLI's
 * stdout/stderr split (e.g. lifecycle:start's admin-command error notices).
 * Same routing as [logWarn] (JVM/Android → `System.err`, iOS → `println`); the
 * two are kept distinct so callers say what they mean rather than tagging every
 * error as a "warning".
 */
expect fun logErr(message: String)
