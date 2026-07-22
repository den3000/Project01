package ru.den.writes.code.agenticHub.mcps.projectfs

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Constructors for the JSON-schema properties the tools declare.
 *
 * Five tools share four arguments — `path`, `subdir`, `ext`, `limit` — and spelling each
 * out inline made the server file mostly schema. Naming them once also keeps the wording
 * a model sees identical everywhere the same argument appears.
 */
internal fun stringProperty(description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
}

internal fun intProperty(description: String): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
}

internal fun boolProperty(description: String): JsonObject = buildJsonObject {
    put("type", "boolean")
    put("description", description)
}

internal fun pathProperty(example: String): JsonObject =
    stringProperty("Path relative to the project root, e.g. \"$example\".")

internal fun subdirProperty(description: String): JsonObject = stringProperty(description)

internal fun extProperty(example: String): JsonObject =
    stringProperty("Comma-separated extensions to keep, e.g. \"$example\".")
