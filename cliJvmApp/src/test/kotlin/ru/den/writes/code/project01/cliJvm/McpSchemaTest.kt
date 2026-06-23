package ru.den.writes.code.project01.cliJvm

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class McpSchemaTest {

    @Test
    fun `when ToolSchema converted - then JSON-Schema object with type, properties and required`() {
        // given
        val props = buildJsonObject { put("city", buildJsonObject { put("type", "string") }) }
        val schema = ToolSchema(properties = props, required = listOf("city"))

        // when
        val json = schema.toJsonSchema()

        // then
        assertEquals("object", json["type"]?.jsonPrimitive?.content)
        assertEquals(props, json["properties"]?.jsonObject)
        assertEquals(listOf(JsonPrimitive("city")), json["required"]?.jsonArray?.toList())
    }

    @Test
    fun `when args object converted - then primitives unwrapped to plain values`() {
        // given
        val args = buildJsonObject {
            put("city", "Paris")
            put("count", 3)
            put("metric", true)
        }

        // when
        val map = args.toArgMap()

        // then
        assertEquals("Paris", map["city"])
        assertEquals(3L, map["count"])
        assertEquals(true, map["metric"])
    }
}
