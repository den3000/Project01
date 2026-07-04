package ru.den.writes.code.agenticHub.platform.network.di

import org.koin.core.module.Module

/**
 * Koin module that binds the platform's shared [io.ktor.client.HttpClient].
 *
 * Expressed per target via `actual`: the engine and its plugins differ by platform
 * (JVM uses the Java engine), so the binding body is platform-specific — the reason
 * this is an `expect fun` returning a [Module] rather than a shared factory. See
 * agenticHubClient/DI.md.
 */
internal expect fun networkModule(): Module

/**
 * The platform's HTTP-client Koin module. **Eager** top-level `val`: on android/ios
 * the `actual` is still `TODO()`, so initializing this `val` there throws — and
 * Kotlin/Native inits *every* top-level `val` of the file at once, so a test that
 * only touches [networkModule] still crashes on iOS. That's an honest signal (no
 * HTTP engine wired for that target yet), surfaced via `@IgnoreIos` on the test.
 */
public val networkModule: Module = networkModule()
