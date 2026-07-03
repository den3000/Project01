package ru.den.writes.code.agenticHub.testutils

/**
 * Пропустить тест на Apple-таргетах. iOS `actual`-реализации ряда платформенных
 * модулей (`platform:fileSystem`/`database`) ещё `TODO()`, а Kotlin/Native
 * инициализирует все top-level `val` файла разом — тест, трогающий такой eager
 * `val`, падает при инициализации. Пометка держит тест **видимым как ignored** в
 * iOS-репорте (честное «iOS не готов»), не тормозя JVM-прогон.
 *
 * JVM/Android — no-op (тест выполняется); iOS — `kotlin.test.Ignore`.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
expect annotation class IgnoreIos()
