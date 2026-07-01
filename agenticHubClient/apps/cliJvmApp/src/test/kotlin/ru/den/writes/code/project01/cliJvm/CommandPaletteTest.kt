package ru.den.writes.code.project01.cliJvm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The command palette catalog — the single list the TUI palette renders. Pins
 * that every row names a `/`-command and that each action maps to the right
 * kind, so the palette and the `/`-command mapper stay in step.
 */
class CommandPaletteTest {

    private val catalog = commandCatalog()

    @Test
    fun `when listing the catalog - then every row names a slash command with help`() {
        // when - then
        assertTrue(catalog.isNotEmpty())
        assertTrue(catalog.all { it.name.startsWith("/") }, "names: ${catalog.map { it.name }}")
        assertTrue(catalog.all { it.help.isNotBlank() })
    }

    @Test
    fun `when a command chooses from a set - then it maps to a picker of that kind`() {
        // when - then
        assertEquals(PaletteAction.Pick(PickerKind.Profile), actionOf("/profile"))
        assertEquals(PaletteAction.Pick(PickerKind.Task), actionOf("/task"))
        assertEquals(PaletteAction.Pick(PickerKind.Branch), actionOf("/branch"))
        assertEquals(PaletteAction.Pick(PickerKind.MemoryMode), actionOf("/agent mode"))
    }

    @Test
    fun `when a command takes no argument - then it maps to a Run or Reuse`() {
        // when - then
        assertEquals(PaletteAction.Run(SessionCommand.Checkpoint), actionOf("/branch show"))
        assertEquals(PaletteAction.Reuse, actionOf("/reuse"))
    }

    @Test
    fun `when a command takes free text - then it maps to a prefill stub ending in a space`() {
        // when
        val action = actionOf("/rule")

        // then
        assertTrue(action is PaletteAction.Prefill && action.stub == "/rule ", "was $action")
    }

    private fun actionOf(name: String): PaletteAction = catalog.first { it.name == name }.action
}
