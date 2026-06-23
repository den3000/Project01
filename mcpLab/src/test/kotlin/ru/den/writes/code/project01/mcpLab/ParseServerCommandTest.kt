package ru.den.writes.code.project01.mcpLab

import kotlin.test.Test
import kotlin.test.assertEquals

class ParseServerCommandTest {

    @Test
    fun `when no arguments - then default everything server`() {
        // given
        val args = emptyArray<String>()

        // when
        val actual = parseServerCommand(args)

        // then
        val expected = listOf("npx", "-y", "@modelcontextprotocol/server-everything")
        assertEquals(expected, actual)
    }

    @Test
    fun `when arguments given - then they are the command verbatim`() {
        // given
        val args = arrayOf("npx", "-y", "@dangahagan/weather-mcp@latest")

        // when
        val actual = parseServerCommand(args)

        // then
        assertEquals(args.toList(), actual)
    }
}
