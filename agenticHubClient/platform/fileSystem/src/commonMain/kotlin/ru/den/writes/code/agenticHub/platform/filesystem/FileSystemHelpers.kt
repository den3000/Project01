package ru.den.writes.code.agenticHub.platform.filesystem

/**
 * The current user's home directory as an absolute path. Callers build their
 * own subpaths under it (e.g. `${homeDirectory()}/.project01-cli/…`).
 *
 * JVM reads `user.home`; iOS/Android are `TODO()` until they grow a real local
 * store (iOS would use `NSHomeDirectory`/`NSDocumentDirectory`, Android an
 * app-specific dir), so touching it there throws.
 */
public expect fun homeDirectory(): String
