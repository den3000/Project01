package ru.den.writes.code.project01.mcpLab

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatToolListTest {

    @Test
    fun `when tools present - then count header and name dash description lines`() {
        // given
        val tools = listOf(
            ToolInfo("echo", "Echoes back the input"),
            ToolInfo("add", "Adds two numbers"),
        )

        // when
        val actual = formatToolList(tools)

        // then
        val expected = """
            2 tool(s):
              • echo — Echoes back the input
              • add — Adds two numbers
        """.trimIndent()
        assertEquals(expected, actual)
    }

    @Test
    fun `when description null or blank - then only the tool name`() {
        // given
        val tools = listOf(
            ToolInfo("noDesc", null),
            ToolInfo("blankDesc", "   "),
        )

        // when
        val actual = formatToolList(tools)

        // then
        val expected = """
            2 tool(s):
              • noDesc
              • blankDesc
        """.trimIndent()
        assertEquals(expected, actual)
    }

    @Test
    fun `when no tools - then zero tools line`() {
        // when - then
        assertEquals("0 tools.", formatToolList(emptyList()))
    }
}
