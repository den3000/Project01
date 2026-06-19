package ru.den.writes.code.project01.cliJvm.memory

import ru.den.writes.code.project01.shared.memory.MemoryMode
import ru.den.writes.code.project01.shared.memory.ProfileSection
import ru.den.writes.code.project01.shared.memory.TaskNotes
import ru.den.writes.code.project01.shared.memory.TaskStage
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryProviderProfileTest {

    @Test
    fun `when memoryLayerFor null - then identical to memoryLayer for the active profile`() {
        withTempMemoryRoot { root ->
            // given
            val store = MemoryStore(root).apply {
                addNamedProfileItem("coder", ProfileSection.STYLE, "write code")
            }
            val provider = MemoryProvider(store, MemoryMode.PREAMBLE, initialProfileName = "coder")

            // when
            val viaNull = provider.memoryLayerFor(null)
            val viaLegacy = provider.memoryLayer()

            // then
            assertEquals(viaLegacy, viaNull)
            assertTrue(viaNull.isNotEmpty(), "active profile should produce a non-empty layer")
            assertTrue(viaNull.first().text.contains("write code"))
        }
    }

    @Test
    fun `when memoryLayerFor a fixed profile - then uses it and ignores the active profile`() {
        withTempMemoryRoot { root ->
            // given
            val store = MemoryStore(root).apply {
                addNamedProfileItem("planner", ProfileSection.STYLE, "plan carefully")
                addNamedProfileItem("coder", ProfileSection.STYLE, "write code")
            }
            // active profile is "coder", but the turn is routed to "planner"
            val provider = MemoryProvider(store, MemoryMode.PREAMBLE, initialProfileName = "coder")

            // when
            val text = provider.memoryLayerFor("planner").first().text

            // then
            assertTrue(text.contains("plan carefully"), "should use the requested profile")
            assertFalse(text.contains("write code"), "should not leak the active profile")
        }
    }

    @Test
    fun `when memoryLayerFor a fixed profile - then rules and task still come from the live store`() {
        withTempMemoryRoot { root ->
            // given
            val store = MemoryStore(root).apply {
                addNamedProfileItem("planner", ProfileSection.STYLE, "plan carefully")
                addRule("no frameworks")
                saveTask(TaskNotes("auth", goal = "ship login", stage = TaskStage.PLANNING))
            }
            // no active profile set — the fixed routed profile stands alone
            val provider = MemoryProvider(store, MemoryMode.PREAMBLE, initialTaskId = "auth")

            // when
            val text = provider.memoryLayerFor("planner").first().text

            // then
            assertTrue(text.contains("plan carefully"), "fixed profile present")
            assertTrue(text.contains("no frameworks"), "shared rule present")
            assertTrue(text.contains("[Current Task]"), "shared task present")
        }
    }

    private inline fun withTempMemoryRoot(block: (java.io.File) -> Unit) {
        val dir = Files.createTempDirectory("project01-mem-profile-").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }
}
