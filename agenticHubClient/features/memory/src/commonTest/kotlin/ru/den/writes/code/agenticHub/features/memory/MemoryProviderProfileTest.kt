package ru.den.writes.code.agenticHub.features.memory

import ru.den.writes.code.agenticHub.platform.filesystem.LocalFileSystem
import ru.den.writes.code.agenticHub.platform.filesystem.di.fileSystemTestModule
import org.koin.dsl.koinApplication
import ru.den.writes.code.agenticHub.features.memory.MemoryMode
import ru.den.writes.code.agenticHub.features.memory.ProfileSection
import ru.den.writes.code.agenticHub.features.memory.TaskNotes
import ru.den.writes.code.agenticHub.features.memory.TaskStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryProviderProfileTest {

    @Test
    fun `when memoryLayerFor null - then identical to memoryLayer for the active profile`() {
        withFakeMemoryRoot { root, fs ->
            // given
            val store = FileMemoryStore(root, fs).apply {
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
        withFakeMemoryRoot { root, fs ->
            // given
            val store = FileMemoryStore(root, fs).apply {
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
        withFakeMemoryRoot { root, fs ->
            // given
            val store = FileMemoryStore(root, fs).apply {
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

    @Test
    fun `when profileDataForAgent gets a pinned name - then that profile comes back whole`() {
        withFakeMemoryRoot { root, fs ->
            // given — the pinned profile carries two different sections
            val store = FileMemoryStore(root, fs).apply {
                addNamedProfileItem("planner", ProfileSection.STYLE, "plan carefully")
                addNamedProfileItem("planner", ProfileSection.CONSTRAINTS, "no frameworks")
                addNamedProfileItem("coder", ProfileSection.STYLE, "write code")
            }
            val provider = MemoryProvider(store, MemoryMode.PREAMBLE, initialProfileName = "coder")

            // when
            val actual = provider.profileDataForAgent("planner")

            // then — every section of the pinned profile, none of the active one
            assertEquals(listOf("plan carefully"), actual?.items(ProfileSection.STYLE))
            assertEquals(listOf("no frameworks"), actual?.items(ProfileSection.CONSTRAINTS))
        }
    }

    @Test
    fun `when profileDataForAgent gets null - then the session active profile is used`() {
        withFakeMemoryRoot { root, fs ->
            // given
            val store = FileMemoryStore(root, fs).apply {
                addNamedProfileItem("coder", ProfileSection.STYLE, "write code")
            }
            val provider = MemoryProvider(store, MemoryMode.PREAMBLE, initialProfileName = "coder")

            // when
            val actual = provider.profileDataForAgent(null)

            // then
            assertEquals(listOf("write code"), actual?.items(ProfileSection.STYLE))
        }
    }

    @Test
    fun `when the pinned profile does not exist - then the unnamed profile is the fallback`() {
        withFakeMemoryRoot { root, fs ->
            // given — nothing named "ghost" was ever created
            val store = FileMemoryStore(root, fs).apply {
                addProfileItem(ProfileSection.STYLE, "house style")
            }
            val provider = MemoryProvider(store, MemoryMode.PREAMBLE)

            // when
            val actual = provider.profileDataForAgent("ghost")

            // then
            assertEquals(listOf("house style"), actual?.items(ProfileSection.STYLE))
        }
    }

    private inline fun withFakeMemoryRoot(block: (root: String, fs: LocalFileSystem) -> Unit) {
        val fs = koinApplication { modules(fileSystemTestModule) }.koin.get<LocalFileSystem>()
        block("/mem", fs)
    }
}
