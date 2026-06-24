package ru.den.writes.code.project01.cliJvm.clicontrols

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PROTOTYPE demo: the startup-argv side — splitting one argv into several controls
 * and running the declarative `requires` / `excludes` checks — plus a couple of
 * catalog-integrity sanity tests.
 */
class CliControlsBatchTest {

    private val parser = CliControlsParser()

    //region argv splitting

    @Test
    fun `when an argv carries several controls - then each parses and the batch is valid`() {
        // when
        val r = parser.parseArgv(listOf("-prompt", "hi", "-strategy", "window", "keepLast", "8"))

        // then
        assertTrue(r.isValid, "errors: ${r.errors.map { it.message }}")
        assertEquals(listOf("prompt hi", "strategy window [keepLast 8]"), r.controls.map { it.render() })
    }

    @Test
    fun `when a free-text value has spaces in one argv slot - then it stays one value`() {
        // when — the shell already made "do x" a single arg
        val r = parser.parseArgv(listOf("-prompt", "do x and y"))

        // then
        assertEquals(listOf("prompt do x and y"), r.controls.map { it.render() })
    }
    //endregion

    //region cross-validation

    @Test
    fun `when oneshot is combined with tui - then a conflict is reported`() {
        // when
        val r = parser.parseArgv(listOf("-prompt", "hi", "-oneshot", "-tui"))

        // then — both controls parse, but the declarative exclude fires
        assertTrue(r.controls.size == 3)
        assertTrue(r.errors.any { it is ParseError.Conflicts }, "errors: ${r.errors.map { it.message }}")
    }

    @Test
    fun `when a feed is split both by line and by chunk - then a conflict is reported`() {
        // when — byLine and chunkChars are mutually exclusive subs of feedFile
        val r = parser.parseArgv(listOf("-feedFile", "d.txt", "byLine", "chunkChars", "100"))

        // then
        assertTrue(r.errors.any { it is ParseError.Conflicts }, "errors: ${r.errors.map { it.message }}")
    }

    @Test
    fun `when oneshot is combined with an mcp server - then a conflict is reported`() {
        // when — MCP tools are Chat-only
        val r = parser.parseArgv(listOf("-prompt", "hi", "-oneshot", "-mcpServer", "mcpLab --serve"))

        // then
        assertTrue(r.errors.any { it is ParseError.Conflicts }, "errors: ${r.errors.map { it.message }}")
    }
    //endregion

    //region catalog integrity

    @Test
    fun `when inspecting subcommands - then every parent chain roots at a top-level control`() {
        // given
        val topArgs = CliControls.all.filter { it.isTopLevel }.map { it.arg }.toSet()

        // when - then
        CliControls.all.filter { !it.isTopLevel }.forEach { spec ->
            val root = spec.parent!!.first()
            assertTrue(root in topArgs, "sub '${spec.token}' roots at '$root' which has no top-level control")
        }
    }

    @Test
    fun `when inspecting top-level controls - then each arg appears once so lookup is deterministic`() {
        // when
        val dups = CliControls.all.filter { it.isTopLevel }.groupBy { it.arg }.filterValues { it.size > 1 }

        // then
        assertTrue(dups.isEmpty(), "duplicate top-level args: ${dups.keys}")
    }
    //endregion
}
