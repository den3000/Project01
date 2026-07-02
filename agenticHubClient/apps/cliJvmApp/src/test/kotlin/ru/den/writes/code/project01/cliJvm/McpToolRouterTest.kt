package ru.den.writes.code.project01.cliJvm

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import ru.den.writes.code.agenticHub.features.llm.ToolCall
import ru.den.writes.code.agenticHub.features.llm.ToolDefinition
import ru.den.writes.code.agenticHub.features.llm.ToolExecutor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class McpToolRouterTest {

    @Test
    fun `when servers offer disjoint tools - then toolDefs is their union in order`() {
        // given
        val weather = route("weather", def("current_weather"))
        val files = route("files", def("save_document"), def("append_to_document"))

        // when
        val router = McpToolRouter(listOf(weather, files))

        // then
        assertEquals(
            listOf("current_weather", "save_document", "append_to_document"),
            router.toolDefs.map { it.name },
        )
    }

    @Test
    fun `when a call names a tool - then it routes to the owning server`() = runBlocking {
        // given
        val weather = RecordingExecutor("18C")
        val files = RecordingExecutor("saved")
        val router = McpToolRouter(
            listOf(
                McpToolRouter.Route(weather, listOf(def("current_weather"))),
                McpToolRouter.Route(files, listOf(def("save_document"))),
            ),
        )

        // when
        val out = router.execute(ToolCall("save_document", buildJsonObject {}))

        // then
        assertEquals("saved", out)
        assertEquals(listOf("save_document"), files.calls.map { it.name })
        assertTrue(weather.calls.isEmpty())
    }

    @Test
    fun `when two servers offer the same tool name - then construction fails fast`() {
        // when - then
        val ex = assertFailsWith<IllegalArgumentException> {
            McpToolRouter(
                listOf(
                    route("a", def("current_weather")),
                    route("b", def("current_weather")),
                ),
            )
        }
        assertTrue(ex.message!!.contains("current_weather"))
    }

    @Test
    fun `when a call names an unknown tool - then execute fails`() {
        // given
        val router = McpToolRouter(listOf(route("a", def("current_weather"))))

        // when - then
        assertFailsWith<IllegalStateException> {
            runBlocking { router.execute(ToolCall("nope", buildJsonObject {})) }
        }
    }

    private fun def(name: String): ToolDefinition =
        ToolDefinition(name = name, description = null, parameters = JsonObject(emptyMap()))

    private fun route(output: String, vararg defs: ToolDefinition): McpToolRouter.Route =
        McpToolRouter.Route(RecordingExecutor(output), defs.toList())

    private class RecordingExecutor(private val output: String) : ToolExecutor {
        val calls = mutableListOf<ToolCall>()
        override suspend fun execute(call: ToolCall): String {
            calls += call
            return output
        }
    }
}
