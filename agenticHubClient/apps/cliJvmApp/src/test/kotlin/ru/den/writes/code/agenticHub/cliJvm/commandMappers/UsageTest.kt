package ru.den.writes.code.agenticHub.cliJvm.commandMappers

import ru.den.writes.code.agenticHub.cliJvm.cliargs.USAGE
import kotlin.test.Test
import kotlin.test.assertTrue

/** Guards that the hand-written [ru.den.writes.code.agenticHub.cliJvm.cliargs.USAGE] hint stays in step with the catalog grammar. */
class UsageTest {

    @Test
    fun `usage names the core controls in the catalog grammar`() {
        // when - then — these are the spellings that changed in the migration
        listOf("-prompt", "-agent", "mode <none|system|preamble>", "-session clear", "-rule", "/agent mode")
            .forEach { assertTrue(it in USAGE, "USAGE should mention '$it', was:\n${USAGE}") }
    }
}
