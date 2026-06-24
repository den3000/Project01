package ru.den.writes.code.project01.cliJvm.plain

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ru.den.writes.code.project01.cliJvm.mcpToolLines
import ru.den.writes.code.project01.shared.agent.ExecutedToolCall
import ru.den.writes.code.project01.shared.llm.ToolCall
import kotlin.test.Test
import kotlin.test.assertEquals

class McpPlainViewTest {

    private val call = ExecutedToolCall(
        ToolCall("current_weather", buildJsonObject { put("city", "Paris") }),
        "Paris, France: clear sky, 25.5°C, wind 3.8 km/h",
    )

    @Test
    fun `when one tool call - then call, result, model and prompt lines`() {
        // when
        val lines = mcpToolLines(listOf(call), "gemini-2.5-flash")

        // then
        assertEquals(
            listOf(
                """[tool] current_weather({"city":"Paris"})""",
                "[tool] → Paris, France: clear sky, 25.5°C, wind 3.8 km/h",
                "model: gemini-2.5-flash",
                "prompt: Paris, France: clear sky, 25.5°C, wind 3.8 km/h",
            ),
            lines,
        )
    }

    @Test
    fun `when rendered as a plain view - then all lines go to stdout and none to stderr`() {
        // given
        val view = McpPlainView(listOf(call), "gemini-2.5-flash")

        // when - then
        assertEquals(mcpToolLines(listOf(call), "gemini-2.5-flash"), view.stdout())
        assertEquals(emptyList<String>(), view.stderr())
    }
}
