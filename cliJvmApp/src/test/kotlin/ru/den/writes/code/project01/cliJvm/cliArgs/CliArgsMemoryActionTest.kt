package ru.den.writes.code.project01.cliJvm.cliArgs

import ru.den.writes.code.project01.cliJvm.CliArgs
import ru.den.writes.code.project01.cliJvm.CliArgsException
import ru.den.writes.code.project01.cliJvm.memory.ProfileSection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CliArgsMemoryActionTest {

    //region -memory show / unknown / mutex

    @Test
    fun `when -memory show passed - then Memory action is Show`() {
        // given
        val args = arrayOf("-memory", "show")

        // when
        val parsed = parseCliArgsWithDummyKeys(*args)

        // then
        val memory = assertIs<CliArgs.Memory>(parsed)
        assertEquals(CliArgs.MemoryAction.Show, memory.action)
    }

    @Test
    fun `when -memory has unknown subcommand - then InvalidArgumentValue on -memory`() {
        // given
        val args = arrayOf("-memory", "wat")

        // when
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parseCliArgsWithDummyKeys(*args)
        }

        // then
        assertEquals("-memory", ex.argName)
    }

    @Test
    fun `when -memory and -oneshot used together - then InvalidArgumentValue on -memory`() {
        // given
        val args = arrayOf("-memory", "show", "-oneshot", "-prompt", "hi")

        // when
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parseCliArgsWithDummyKeys(*args)
        }

        // then
        assertTrue(ex.message!!.contains("-memory"))
    }
    //endregion

    //region -memory profile (unnamed, free text + sections)

    @Test
    fun `when -memory profile followed by free text - then SetProfile carries joined text`() {
        // given
        val args = arrayOf("-memory", "profile", "I", "write", "Kotlin")

        // when
        val parsed = parseCliArgsWithDummyKeys(*args)

        // then
        val memory = assertIs<CliArgs.Memory>(parsed)
        assertEquals(CliArgs.MemoryAction.SetProfile("I write Kotlin"), memory.action)
    }

    @Test
    fun `when -memory profile section text - then AddProfileItem with that section`() {
        // given
        val args = arrayOf("-memory", "profile", "style", "кратко,", "на", "русском")

        // when
        val parsed = parseCliArgsWithDummyKeys(*args)

        // then
        val memory = assertIs<CliArgs.Memory>(parsed)
        val expected = CliArgs.MemoryAction.AddProfileItem(ProfileSection.STYLE, "кратко, на русском")
        assertEquals(expected, memory.action)
    }

    @Test
    fun `when -memory profile section clear - then ClearProfileSection for that section`() {
        // given
        val args = arrayOf("-memory", "profile", "constraints", "clear")

        // when
        val parsed = parseCliArgsWithDummyKeys(*args)

        // then
        val memory = assertIs<CliArgs.Memory>(parsed)
        val expected = CliArgs.MemoryAction.ClearProfileSection(ProfileSection.CONSTRAINTS)
        assertEquals(expected, memory.action)
    }

    @Test
    fun `when -memory profile clear - then ClearProfile action`() {
        // given
        val args = arrayOf("-memory", "profile", "clear")

        // when
        val parsed = parseCliArgsWithDummyKeys(*args)

        // then
        val memory = assertIs<CliArgs.Memory>(parsed)
        assertEquals(CliArgs.MemoryAction.ClearProfile, memory.action)
    }

    @Test
    fun `when -memory profile section with no text - then MissingRequiredArgument on -memory`() {
        // given
        val args = arrayOf("-memory", "profile", "style")

        // when
        val ex = assertFailsWith<CliArgsException.MissingRequiredArgument> {
            parseCliArgsWithDummyKeys(*args)
        }

        // then
        assertEquals("-memory", ex.argName)
    }
    //endregion

    //region -memory profile (named)

    @Test
    fun `when -memory profile-list - then ListProfiles action`() {
        // given
        val args = arrayOf("-memory", "profile-list")

        // when
        val parsed = parseCliArgsWithDummyKeys(*args)

        // then
        val memory = assertIs<CliArgs.Memory>(parsed)
        assertEquals(CliArgs.MemoryAction.ListProfiles, memory.action)
    }

    @Test
    fun `when -memory profile-show name - then ShowProfile with that name`() {
        // given
        val args = arrayOf("-memory", "profile-show", "kotlin-senior")

        // when
        val parsed = parseCliArgsWithDummyKeys(*args)

        // then
        val memory = assertIs<CliArgs.Memory>(parsed)
        assertEquals(CliArgs.MemoryAction.ShowProfile("kotlin-senior"), memory.action)
    }

    @Test
    fun `when -memory profile name only - then TouchProfile action`() {
        // given
        val args = arrayOf("-memory", "profile", "kotlin-senior")

        // when
        val parsed = parseCliArgsWithDummyKeys(*args)

        // then
        val memory = assertIs<CliArgs.Memory>(parsed)
        assertEquals(CliArgs.MemoryAction.TouchProfile("kotlin-senior"), memory.action)
    }

    @Test
    fun `when -memory profile name section text - then AddNamedProfileItem for that name and section`() {
        // given
        val args = arrayOf("-memory", "profile", "kotlin-senior", "style", "кратко,", "на", "русском")

        // when
        val parsed = parseCliArgsWithDummyKeys(*args)

        // then
        val memory = assertIs<CliArgs.Memory>(parsed)
        val expected = CliArgs.MemoryAction.AddNamedProfileItem(
            name = "kotlin-senior",
            section = ProfileSection.STYLE,
            text = "кратко, на русском",
        )
        assertEquals(expected, memory.action)
    }

    @Test
    fun `when -memory profile name section clear - then ClearNamedProfileSection for that name and section`() {
        // given
        val args = arrayOf("-memory", "profile", "kotlin-senior", "constraints", "clear")

        // when
        val parsed = parseCliArgsWithDummyKeys(*args)

        // then
        val memory = assertIs<CliArgs.Memory>(parsed)
        val expected = CliArgs.MemoryAction.ClearNamedProfileSection(
            name = "kotlin-senior",
            section = ProfileSection.CONSTRAINTS,
        )
        assertEquals(expected, memory.action)
    }

    @Test
    fun `when -memory profile name clear - then ClearNamedProfile with that name`() {
        // given
        val args = arrayOf("-memory", "profile", "kotlin-senior", "clear")

        // when
        val parsed = parseCliArgsWithDummyKeys(*args)

        // then
        val memory = assertIs<CliArgs.Memory>(parsed)
        assertEquals(CliArgs.MemoryAction.ClearNamedProfile("kotlin-senior"), memory.action)
    }
    //endregion

    //region -memory rule and task

    @Test
    fun `when -memory rule add followed by text - then AddRule with joined text`() {
        // given
        val args = arrayOf("-memory", "rule", "add", "No", "Spring")

        // when
        val parsed = parseCliArgsWithDummyKeys(*args)

        // then
        val memory = assertIs<CliArgs.Memory>(parsed)
        assertEquals(CliArgs.MemoryAction.AddRule("No Spring"), memory.action)
    }

    @Test
    fun `when -memory rule rm with id - then RemoveRule with that id`() {
        // given
        val args = arrayOf("-memory", "rule", "rm", "002")

        // when
        val parsed = parseCliArgsWithDummyKeys(*args)

        // then
        val memory = assertIs<CliArgs.Memory>(parsed)
        assertEquals(CliArgs.MemoryAction.RemoveRule("002"), memory.action)
    }

    @Test
    fun `when -memory task with id - then SetTask with that id`() {
        // given
        val args = arrayOf("-memory", "task", "auth-service")

        // when
        val parsed = parseCliArgsWithDummyKeys(*args)

        // then
        val memory = assertIs<CliArgs.Memory>(parsed)
        assertEquals(CliArgs.MemoryAction.SetTask("auth-service"), memory.action)
    }

    @Test
    fun `when -memory task with invalid id shape - then InvalidArgumentValue on -memory`() {
        // given
        // Slash is not in the allowed alphabet for task ids.
        val args = arrayOf("-memory", "task", "bad/id")

        // when
        val ex = assertFailsWith<CliArgsException.InvalidArgumentValue> {
            parseCliArgsWithDummyKeys(*args)
        }

        // then
        assertEquals("-memory", ex.argName)
    }
    //endregion

    private fun parseCliArgsWithDummyKeys(vararg args: String): CliArgs =
        CliArgs.from(
            args = arrayOf(*args),
            geminiApiKey = DUMMY_GEMINI_KEY,
            openRouterApiKey = DUMMY_OPENROUTER_KEY,
            huggingFaceApiKey = DUMMY_HUGGINGFACE_KEY,
        )

    private companion object {
        const val DUMMY_GEMINI_KEY = "test-gemini-key"
        const val DUMMY_OPENROUTER_KEY = "test-openrouter-key"
        const val DUMMY_HUGGINGFACE_KEY = "test-huggingface-key"
    }
}
