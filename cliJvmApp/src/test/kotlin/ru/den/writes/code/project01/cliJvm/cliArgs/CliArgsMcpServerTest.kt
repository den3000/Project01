package ru.den.writes.code.project01.cliJvm.cliArgs

import ru.den.writes.code.project01.cliJvm.CliArgs
import ru.den.writes.code.project01.cliJvm.CliArgsException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class CliArgsMcpServerTest {

    @Test
    fun `when -mcpServer passed in chat - then Chat carries the command verbatim`() {
        // when
        val parsed = parse("-prompt", "hi", "-mcpServer", "mcpLab --serve")

        // then
        val chat = assertIs<CliArgs.Chat>(parsed)
        assertEquals("mcpLab --serve", chat.mcpServer)
    }

    @Test
    fun `when -mcpServer absent - then Chat mcpServer is null`() {
        // when
        val chat = assertIs<CliArgs.Chat>(parse("-prompt", "hi"))

        // then
        assertNull(chat.mcpServer)
    }

    @Test
    fun `when -mcpServer combined with -oneshot - then rejected`() {
        // when - then
        assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parse("-prompt", "hi", "-oneshot", "-mcpServer", "mcpLab --serve")
        }
    }

    private fun parse(vararg args: String): CliArgs =
        CliArgs.from(
            args = arrayOf(*args),
            geminiApiKey = "test-key",
            openRouterApiKey = "test-key",
            huggingFaceApiKey = "test-key",
        )
}
