package ru.den.writes.code.agenticHub.features.memory

import ru.den.writes.code.agenticHub.platform.filesystem.LocalFileSystem
import ru.den.writes.code.agenticHub.platform.filesystem.di.fileSystemTestModule
import org.koin.dsl.koinApplication
import ru.den.writes.code.agenticHub.features.memory.ProfileData
import ru.den.writes.code.agenticHub.features.memory.ProfileSection
import ru.den.writes.code.agenticHub.features.memory.TaskNotes
import ru.den.writes.code.agenticHub.features.memory.TaskStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryStoreTest {

    @Test
    fun `loadProfile returns null when no profile file exists`() = withFakeFs { root, fs ->
        val store = FileMemoryStore(root, fs)
        assertNull(store.loadProfile())
    }

    @Test
    fun `saveProfile and loadProfile round-trip`() = withFakeFs { root, fs ->
        val store = FileMemoryStore(root, fs)
        store.saveProfile("Я пишу только на Kotlin")
        assertEquals("Я пишу только на Kotlin", store.loadProfile())
    }

    @Test
    fun `saveProfile with blank deletes the file`() = withFakeFs { root, fs ->
        val store = FileMemoryStore(root, fs)
        store.saveProfile("first")
        store.saveProfile("   ")
        assertNull(store.loadProfile())
        assertFalse(fs.exists("$root/${FileMemoryStore.PROFILE_FILE_NAME}"))
    }

    @Test
    fun `loadProfileData on a legacy free-text profile returns it as freeText`() = withFakeFs { root, fs ->
        val store = FileMemoryStore(root, fs)
        store.saveProfile("Я пишу только на Kotlin")
        val data = assertNotNull(store.loadProfileData())
        assertEquals("Я пишу только на Kotlin", data.freeText)
        assertEquals(emptyList(), data.style)
    }

    @Test
    fun `loadProfileData parses structured sections`() = withFakeFs { root, fs ->
        val store = FileMemoryStore(root, fs)
        store.saveProfile(
            """
            ## Style
            - кратко
            - русский
            ## Constraints
            - Kotlin
            """.trimIndent()
        )
        val data = assertNotNull(store.loadProfileData())
        assertNull(data.freeText)
        assertEquals(listOf("кратко", "русский"), data.style)
        assertEquals(listOf("Kotlin"), data.constraints)
    }

    @Test
    fun `saveProfileData with empty ProfileData deletes the file`() = withFakeFs { root, fs ->
        val store = FileMemoryStore(root, fs)
        store.saveProfile("anything")
        store.saveProfileData(ProfileData())
        assertNull(store.loadProfileData())
        assertFalse(fs.exists("$root/${FileMemoryStore.PROFILE_FILE_NAME}"))
    }

    @Test
    fun `addProfileItem appends two bullets and persists them`() = withFakeFs { root, fs ->
        val store = FileMemoryStore(root, fs)
        store.addProfileItem(ProfileSection.STYLE, "кратко")
        val after = store.addProfileItem(ProfileSection.STYLE, "русский")
        assertEquals(listOf("кратко", "русский"), after.style)
        // Re-read from disk to make sure the second write didn't overwrite the first.
        val reread = assertNotNull(store.loadProfileData())
        assertEquals(listOf("кратко", "русский"), reread.style)
    }

    @Test
    fun `clearProfileSection drops only the chosen section`() = withFakeFs { root, fs ->
        val store = FileMemoryStore(root, fs)
        store.addProfileItem(ProfileSection.STYLE, "a")
        store.addProfileItem(ProfileSection.FORMAT, "b")
        store.addProfileItem(ProfileSection.CONSTRAINTS, "c")
        val after = store.clearProfileSection(ProfileSection.STYLE)
        assertEquals(emptyList(), after.style)
        assertEquals(listOf("b"), after.format)
        assertEquals(listOf("c"), after.constraints)
        val reread = assertNotNull(store.loadProfileData())
        assertEquals(emptyList(), reread.style)
        assertEquals(listOf("b"), reread.format)
    }

    @Test
    fun `clearProfile deletes the entire profile file`() = withFakeFs { root, fs ->
        val store = FileMemoryStore(root, fs)
        store.addProfileItem(ProfileSection.STYLE, "a")
        store.clearProfile()
        assertNull(store.loadProfileData())
        assertFalse(fs.exists("$root/${FileMemoryStore.PROFILE_FILE_NAME}"))
    }

    // --- named profiles ---

    @Test
    fun `listProfileNames is empty when the profiles dir is fresh`() = withFakeFs { root, fs ->
        val store = FileMemoryStore(root, fs)
        assertEquals(emptyList(), store.listProfileNames())
    }

    @Test
    fun `addNamedProfileItem creates the file and persists between reads`() = withFakeFs { root, fs ->
        val store = FileMemoryStore(root, fs)
        store.addNamedProfileItem("kotlin-senior", ProfileSection.STYLE, "кратко")
        store.addNamedProfileItem("kotlin-senior", ProfileSection.CONSTRAINTS, "Kotlin")
        val reread = assertNotNull(store.loadNamedProfile("kotlin-senior"))
        assertEquals(listOf("кратко"), reread.style)
        assertEquals(listOf("Kotlin"), reread.constraints)
    }

    @Test
    fun `loadNamedProfile returns null for an unknown name`() = withFakeFs { root, fs ->
        val store = FileMemoryStore(root, fs)
        assertNull(store.loadNamedProfile("missing"))
    }

    @Test
    fun `listProfileNames sorts and skips non-md files`() = withFakeFs { root, fs ->
        val store = FileMemoryStore(root, fs)
        store.touchNamedProfile("zeta")
        store.touchNamedProfile("alpha")
        store.touchNamedProfile("middle")
        fs.writeText("$root/${FileMemoryStore.PROFILES_DIR}/README", "ignore me")
        assertEquals(listOf("alpha", "middle", "zeta"), store.listProfileNames())
    }

    @Test
    fun `clearNamedProfileSection wipes one section and keeps the rest`() = withFakeFs { root, fs ->
        val store = FileMemoryStore(root, fs)
        store.addNamedProfileItem("a", ProfileSection.STYLE, "s")
        store.addNamedProfileItem("a", ProfileSection.CONSTRAINTS, "c")
        store.clearNamedProfileSection("a", ProfileSection.STYLE)
        val data = assertNotNull(store.loadNamedProfile("a"))
        assertEquals(emptyList(), data.style)
        assertEquals(listOf("c"), data.constraints)
    }

    @Test
    fun `clearNamedProfile removes the file and reports success`() = withFakeFs { root, fs ->
        val store = FileMemoryStore(root, fs)
        store.addNamedProfileItem("a", ProfileSection.STYLE, "s")
        assertTrue(store.clearNamedProfile("a"))
        assertNull(store.loadNamedProfile("a"))
        assertFalse(store.clearNamedProfile("a"))  // second time the file is already gone
    }

    @Test
    fun `clearAllProfiles removes every named profile and the unnamed default`() = withFakeFs { root, fs ->
        val store = FileMemoryStore(root, fs)
        store.saveProfile("legacy free text")
        store.addNamedProfileItem("work", ProfileSection.STYLE, "кратко")
        store.addNamedProfileItem("home", ProfileSection.STYLE, "подробно")
        assertEquals(2, store.clearAllProfiles())
        assertEquals(emptyList(), store.listProfileNames())
        assertNull(store.loadProfileData())
    }

    @Test
    fun `touchNamedProfile creates an empty file when none exists`() = withFakeFs { root, fs ->
        val store = FileMemoryStore(root, fs)
        store.touchNamedProfile("fresh")
        assertTrue(fs.exists("$root/${FileMemoryStore.PROFILES_DIR}/fresh.md"))
        // Empty file → loadNamedProfile still returns null (no content to parse).
        assertNull(store.loadNamedProfile("fresh"))
        assertEquals(listOf("fresh"), store.listProfileNames())
    }

    @Test
    fun `touchNamedProfile does not overwrite an existing file`() = withFakeFs { root, fs ->
        val store = FileMemoryStore(root, fs)
        store.addNamedProfileItem("a", ProfileSection.STYLE, "keep me")
        store.touchNamedProfile("a")
        assertEquals(listOf("keep me"), assertNotNull(store.loadNamedProfile("a")).style)
    }

    @Test
    fun `addRule numbers entries starting from 001`() = withFakeFs { root, fs ->
        val store = FileMemoryStore(root, fs)
        val a = store.addRule("Only Kotlin")
        val b = store.addRule("No Spring")
        assertEquals("001", a.id)
        assertEquals("002", b.id)
    }

    @Test
    fun `listRules returns entries in id order`() = withFakeFs { root, fs ->
        val store = FileMemoryStore(root, fs)
        store.addRule("First")
        store.addRule("Second")
        store.addRule("Third")
        val ids = store.listRules().map { it.id }
        assertEquals(listOf("001", "002", "003"), ids)
    }

    @Test
    fun `removeRule deletes the file and does not reuse the id`() = withFakeFs { root, fs ->
        val store = FileMemoryStore(root, fs)
        store.addRule("first")
        store.addRule("second")
        assertTrue(store.removeRule("001"))
        val third = store.addRule("third")
        assertEquals("003", third.id, "deleted ids must not be recycled")
        val remaining = store.listRules().map { it.id }
        assertEquals(listOf("002", "003"), remaining)
    }

    @Test
    fun `removeRule on a missing id returns false`() = withFakeFs { root, fs ->
        val store = FileMemoryStore(root, fs)
        store.addRule("only one")
        assertFalse(store.removeRule("999"))
    }

    @Test
    fun `clearRules deletes every rule file`() = withFakeFs { root, fs ->
        val store = FileMemoryStore(root, fs)
        store.addRule("a")
        store.addRule("b")
        assertEquals(2, store.clearRules())
        assertEquals(emptyList(), store.listRules())
    }

    @Test
    fun `listRules ignores files not matching the NNN-slug shape`() = withFakeFs { root, fs ->
        val store = FileMemoryStore(root, fs)
        store.addRule("valid")
        fs.writeText("$root/${FileMemoryStore.RULES_DIR}/notes.md", "stray")
        fs.writeText("$root/${FileMemoryStore.RULES_DIR}/README", "ignore me")
        assertEquals(listOf("001"), store.listRules().map { it.id })
    }

    @Test
    fun `slugify replaces non-ascii alphanum with dash`() {
        assertEquals("kotlin-only", FileMemoryStore.slugify("Kotlin only"))
        assertEquals("a-b-c", FileMemoryStore.slugify("a! b? c"))
        assertEquals("", FileMemoryStore.slugify("кириллица"))
    }

    @Test
    fun `addRule with pure cyrillic text falls back to rule slug`() = withFakeFs { root, fs ->
        val store = FileMemoryStore(root, fs)
        store.addRule("Запрещено всё")
        val files = fs.listFileNames("$root/${FileMemoryStore.RULES_DIR}")
        assertEquals(listOf("001-rule.md"), files)
    }

    @Test
    fun `saveTask and loadTask round-trip with all sections`() = withFakeFs { root, fs ->
        val store = FileMemoryStore(root, fs)
        val notes = TaskNotes(
            taskId = "auth-service",
            goal = "Сервис авторизации поверх Ktor + JWT",
            stage = TaskStage.PLANNING,
            paused = true,
            notes = listOf("Уже выбран стек: Ktor 3", "Не использовать Spring"),
        )
        store.saveTask(notes)
        val loaded = store.loadTask("auth-service")
        assertEquals(notes, loaded)
    }

    @Test
    fun `when parseTaskNotes sees an unknown stage keyword - then stage is null`() {
        // given
        // A legacy / hand-edited file whose ## Stage holds free text rather
        // than a known keyword — tolerated as "no FSM position yet".
        val raw = "# Task: foo\n## Stage\nin progress maybe\n"

        // when
        val notes = FileMemoryStore.parseTaskNotes("foo", raw)

        // then
        assertNull(notes.stage)
    }

    @Test
    fun `loadTask returns null when no file exists`() = withFakeFs { root, fs ->
        val store = FileMemoryStore(root, fs)
        assertNull(store.loadTask("missing"))
    }

    @Test
    fun `parseTaskNotes on a header-only file returns empty fields`() {
        val notes = FileMemoryStore.parseTaskNotes("foo", "# Task: foo\n")
        assertEquals("foo", notes.taskId)
        assertNull(notes.goal)
        assertNull(notes.stage)
        assertEquals(emptyList(), notes.notes)
    }

    @Test
    fun `parseTaskNotes skips unknown sections`() {
        val raw = """
            # Task: foo
            ## Goal
            G
            ## SomethingElse
            ignored
            ## Notes
            - n1
        """.trimIndent()
        val notes = FileMemoryStore.parseTaskNotes("foo", raw)
        assertEquals("G", notes.goal)
        assertNull(notes.stage)
        assertEquals(listOf("n1"), notes.notes)
    }

    @Test
    fun `appendTaskNote creates the file when missing`() = withFakeFs { root, fs ->
        val store = FileMemoryStore(root, fs)
        store.appendTaskNote("fresh", "first note")
        val loaded = assertNotNull(store.loadTask("fresh"))
        assertEquals(listOf("first note"), loaded.notes)
    }

    @Test
    fun `appendTaskNote keeps prior notes and appends a new one`() = withFakeFs { root, fs ->
        val store = FileMemoryStore(root, fs)
        store.saveTask(TaskNotes("auth", goal = "ship it", notes = listOf("n1")))
        store.appendTaskNote("auth", "n2")
        val loaded = assertNotNull(store.loadTask("auth"))
        assertEquals("ship it", loaded.goal)
        assertEquals(listOf("n1", "n2"), loaded.notes)
    }

    @Test
    fun `renderTaskNotes omits empty sections`() {
        val rendered = FileMemoryStore.renderTaskNotes(TaskNotes("t", goal = "g"))
        assertTrue(rendered.contains("## Goal"))
        assertFalse(rendered.contains("## Stage"))
        assertFalse(rendered.contains("## Notes"))
    }

    @Test
    fun `listTaskIds is sorted alphabetically and ignores non-md files`() = withFakeFs { root, fs ->
        val store = FileMemoryStore(root, fs)
        store.saveTask(TaskNotes("zeta"))
        store.saveTask(TaskNotes("alpha"))
        store.saveTask(TaskNotes("middle"))
        fs.writeText("$root/${FileMemoryStore.TASKS_DIR}/README", "ignore me")
        assertEquals(listOf("alpha", "middle", "zeta"), store.listTaskIds())
    }

    @Test
    fun `deleteTask removes one file and clearTasks removes the rest`() = withFakeFs { root, fs ->
        val store = FileMemoryStore(root, fs)
        store.saveTask(TaskNotes("auth"))
        store.saveTask(TaskNotes("ui"))
        assertTrue(store.deleteTask("auth"))
        assertNull(store.loadTask("auth"))
        assertFalse(store.deleteTask("auth"))  // already gone
        assertEquals(listOf("ui"), store.listTaskIds())
        assertEquals(1, store.clearTasks())
        assertEquals(emptyList(), store.listTaskIds())
    }
}

/**
 * Run [block] against a fresh in-memory filesystem (via [fileSystemTestModule]'s
 * fake) rooted at a fixed path. Local helper so each test stays self-contained —
 * no shared state, no real disk.
 */
private inline fun withFakeFs(block: (root: String, fs: LocalFileSystem) -> Unit) {
    val fs = koinApplication { modules(fileSystemTestModule) }.koin.get<LocalFileSystem>()
    block("/mem", fs)
}
