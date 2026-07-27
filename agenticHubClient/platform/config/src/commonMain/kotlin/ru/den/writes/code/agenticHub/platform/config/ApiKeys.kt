package ru.den.writes.code.agenticHub.platform.config

/** Provider API keys, injected where a model provider is built. */
public data class ApiKeys(
    val gemini: String = "",
    val openRouter: String = "",
    val huggingFace: String = "",
)

/**
 * Resolve one provider key: the process environment wins over the [baked] value
 * `BuildKonfig` compiled in, and a blank env var counts as absent.
 *
 * `BuildKonfig` reads `local.properties` (or the env) at Gradle *configuration* time and
 * bakes the result into the binary — fine for a developer machine, useless for a binary
 * built somewhere that must not see the secret. Reading the env at *runtime* lets a build
 * carry no credentials at all and a deployment (CI, a container) supply them per run;
 * with no env set, local development keeps working off `local.properties` exactly as before.
 *
 * [env] is injected (`System::getenv` at the JVM call site) so the precedence is
 * unit-testable without touching the real process — and keeping it a parameter, rather
 * than a JVM-only default, lets this stay in `commonMain`.
 */
public fun resolveKey(key: ApiKey, baked: String, env: (String) -> String?): String =
    env(key.envVar)?.takeIf { it.isNotBlank() } ?: baked
