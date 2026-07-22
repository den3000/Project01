package ru.den.writes.code.agenticHub.mcps.atimelogger

import java.util.Base64

/**
 * HTTP Basic `Authorization` header value for [user]:[password] — `"Basic " + base64(user:password)`.
 * Pure and unit-tested; the secret itself never leaves this process (set once on the client's
 * `defaultRequest`, never logged).
 */
internal fun basicAuthHeader(user: String, password: String): String =
    "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray(Charsets.UTF_8))
