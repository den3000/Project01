package ru.den.writes.code.project01.cliJvm.command

import ru.den.writes.code.project01.shared.memory.ProfileSection
import kotlin.test.Test
import kotlin.test.assertEquals

/** CliControls front: profile / rule / task entity ops (no prompt) → MemoryOp. */
class CliControlsCommandParserMemoryTest {

    private val parser = CliControlsCommandParser(
        ApiKeys(DUMMY_GEMINI_KEY, DUMMY_OPENROUTER_KEY, DUMMY_HUGGINGFACE_KEY),
    )

    @Test
    fun `when a profile, rule or task entity op is used - then MemoryOp with the matching action`() {
        // given
        val cases = listOf(
            arrayOf("-profile") to MemoryAction.ListProfiles,
            arrayOf("-profile", "kotlin-senior") to MemoryAction.TouchProfile("kotlin-senior"),
            arrayOf("-profile", "work", "style", "terse") to MemoryAction.AddNamedProfileItem("work", ProfileSection.STYLE, "terse"),
            arrayOf("-profile", "work", "style") to MemoryAction.ClearNamedProfileSection("work", ProfileSection.STYLE),
            arrayOf("-profile", "style", "x") to MemoryAction.AddProfileItem(ProfileSection.STYLE, "x"),
            arrayOf("-profile", "clear", "work") to MemoryAction.ClearNamedProfile("work"),
            arrayOf("-profile", "clear") to MemoryAction.ClearAllProfiles,
            arrayOf("-profile", "show", "kotlin-senior") to MemoryAction.ShowProfile("kotlin-senior"),
            arrayOf("-rule", "always kotlin") to MemoryAction.AddRule("always kotlin"),
            arrayOf("-rule", "clear", "003") to MemoryAction.RemoveRule("003"),
            arrayOf("-rule", "clear") to MemoryAction.ClearRules,
            arrayOf("-task", "auth") to MemoryAction.SetTask("auth"),
            arrayOf("-task", "auth", "pause") to MemoryAction.PauseTask("auth"),
            arrayOf("-task", "auth", "resume") to MemoryAction.ResumeTask("auth"),
            arrayOf("-task", "clear", "auth") to MemoryAction.DeleteTask("auth"),
            arrayOf("-task", "clear") to MemoryAction.ClearTasks,
        )

        // when - then — one invariant (entity op → MemoryOp) over an extending list (rule §11.E)
        cases.forEach { (args, action) ->
            assertEquals(CliCommand.MemoryOp(action), parser.parse(args), args.joinToString(" "))
        }
    }

    private companion object {
        const val DUMMY_GEMINI_KEY = "test-gemini-key"
        const val DUMMY_OPENROUTER_KEY = "test-openrouter-key"
        const val DUMMY_HUGGINGFACE_KEY = "test-huggingface-key"
    }
}
