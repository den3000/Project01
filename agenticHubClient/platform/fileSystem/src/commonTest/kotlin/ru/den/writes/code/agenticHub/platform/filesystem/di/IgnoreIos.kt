package ru.den.writes.code.agenticHub.platform.filesystem.di

/**
 * Skip a test on Apple targets. iOS `actual`s of `platform:fileSystem`/`database`
 * are still `TODO()`, so any test touching the eager `fileSystemModule` file
 * crashes on Kotlin/Native at init. Marking such tests keeps them **visible as
 * ignored** in iOS reports (honest "not ready for iOS") without blocking JVM runs.
 * JVM: no-op. iOS: `kotlin.test.Ignore`.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
expect annotation class IgnoreIos()
