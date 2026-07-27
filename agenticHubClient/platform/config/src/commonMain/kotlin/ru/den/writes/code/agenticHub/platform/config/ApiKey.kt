package ru.den.writes.code.agenticHub.platform.config

/**
 * The provider credential slots and the environment-variable / `BuildKonfig` field
 * name each one is read from. This enum is the single source of those names — no
 * other Kotlin code spells them as string literals.
 *
 * The one unavoidable exception is `build.gradle.kts`, where the same strings name
 * the generated `BuildKonfig` fields and the `local.properties`/env keys: a build
 * script is compiled on its own classpath and can't see the classes of the module it
 * builds, so the literals have to live there.
 */
public enum class ApiKey(public val envVar: String) {
    GEMINI("GEMINI_API_KEY"),
    OPEN_ROUTER("OPENROUTER_API_KEY"),
    HUGGING_FACE("HUGGINGFACE_API_KEY"),
}
