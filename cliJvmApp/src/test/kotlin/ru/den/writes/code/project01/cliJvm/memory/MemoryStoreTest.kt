package ru.den.writes.code.project01.cliJvm.memory

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryStoreTest {

    @Test
    fun `loadProfile returns null when no profile file exists`() = withTempDir { root ->
        val store = MemoryStore(root)
        assertNull(store.loadProfile())
    }

    @Test
    fun `saveProfile and loadProfile round-trip`() = withTempDir { root ->
        val store = MemoryStore(root)
        store.saveProfile("Я пишу только на Kotlin")
        assertEquals("Я пишу только на Kotlin", store.loadProfile())
    }

    @Test
    fun `saveProfile with blank deletes the file`() = withTempDir { root ->
        val store = MemoryStore(root)
        store.saveProfile("first")
        store.saveProfile("   ")
        assertNull(store.loadProfile())
        assertFalse(File(root, MemoryStore.PROFILE_FILE_NAME).exists())
    }

    @Test
    fun `loadProfileData on a legacy free-text profile returns it as freeText`() = withTempDir { root ->
        val store = MemoryStore(root)
        store.saveProfile("Я пишу только на Kotlin")
        val data = assertNotNull(store.loadProfileData())
        assertEquals("Я пишу только на Kotlin", data.freeText)
        assertEquals(emptyList(), data.style)
    }

    @Test
    fun `loadProfileData parses structured sections`() = withTempDir { root ->
        val store = MemoryStore(root)
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
    fun `saveProfileData with empty ProfileData deletes the file`() = withTempDir { root ->
        val store = MemoryStore(root)
        store.saveProfile("anything")
        store.saveProfileData(ProfileData())
        assertNull(store.loadProfileData())
        assertFalse(File(root, MemoryStore.PROFILE_FILE_NAME).exists())
    }

    @Test
    fun `addProfileItem appends two bullets and persists them`() = withTempDir { root ->
        val store = MemoryStore(root)
        store.addProfileItem(ProfileSection.STYLE, "кратко")
        val after = store.addProfileItem(ProfileSection.STYLE, "русский")
        assertEquals(listOf("кратко", "русский"), after.style)
        // Re-read from disk to make sure the second write didn't overwrite the first.
        val reread = assertNotNull(store.loadProfileData())
        assertEquals(listOf("кратко", "русский"), reread.style)
    }

    @Test
    fun `clearProfileSection drops only the chosen section`() = withTempDir { root ->
        val store = MemoryStore(root)
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
    fun `clearProfile deletes the entire profile file`() = withTempDir { root ->
        val store = MemoryStore(root)
        store.addProfileItem(ProfileSection.STYLE, "a")
        store.clearProfile()
        assertNull(store.loadProfileData())
        assertFalse(File(root, MemoryStore.PROFILE_FILE_NAME).exists())
    }

    // --- named profiles ---

    @Test
    fun `listProfileNames is empty when the profiles dir is fresh`() = withTempDir { root ->
        val store = MemoryStore(root)
        assertEquals(emptyList(), store.listProfileNames())
    }

    @Test
    fun `addNamedProfileItem creates the file and persists between reads`() = withTempDir { root ->
        val store = MemoryStore(root)
        store.addNamedProfileItem("kotlin-senior", ProfileSection.STYLE, "кратко")
        store.addNamedProfileItem("kotlin-senior", ProfileSection.CONSTRAINTS, "Kotlin")
        val reread = assertNotNull(store.loadNamedProfile("kotlin-senior"))
        assertEquals(listOf("кратко"), reread.style)
        assertEquals(listOf("Kotlin"), reread.constraints)
    }

    @Test
    fun `loadNamedProfile returns null for an unknown name`() = withTempDir { root ->
        val store = MemoryStore(root)
        assertNull(store.loadNamedProfile("missing"))
    }

    @Test
    fun `listProfileNames sorts and skips non-md files`() = withTempDir { root ->
        val store = MemoryStore(root)
        store.touchNamedProfile("zeta")
        store.touchNamedProfile("alpha")
        store.touchNamedProfile("middle")
        File(root, MemoryStore.PROFILES_DIR).resolve("README").writeText("ignore me")
        assertEquals(listOf("alpha", "middle", "zeta"), store.listProfileNames())
    }

    @Test
    fun `clearNamedProfileSection wipes one section and keeps the rest`() = withTempDir { root ->
        val store = MemoryStore(root)
        store.addNamedProfileItem("a", ProfileSection.STYLE, "s")
        store.addNamedProfileItem("a", ProfileSection.CONSTRAINTS, "c")
        store.clearNamedProfileSection("a", ProfileSection.STYLE)
        val data = assertNotNull(store.loadNamedProfile("a"))
        assertEquals(emptyList(), data.style)
        assertEquals(listOf("c"), data.constraints)
    }

    @Test
    fun `clearNamedProfile removes the file and reports success`() = withTempDir { root ->
        val store = MemoryStore(root)
        store.addNamedProfileItem("a", ProfileSection.STYLE, "s")
        assertTrue(store.clearNamedProfile("a"))
        assertNull(store.loadNamedProfile("a"))
        assertFalse(store.clearNamedProfile("a"))  // second time the file is already gone
    }

    @Test
    fun `touchNamedProfile creates an empty file when none exists`() = withTempDir { root ->
        val store = MemoryStore(root)
        store.touchNamedProfile("fresh")
        assertTrue(File(root, MemoryStore.PROFILES_DIR).resolve("fresh.md").exists())
        // Empty file → loadNamedProfile still returns null (no content to parse).
        assertNull(store.loadNamedProfile("fresh"))
        assertEquals(listOf("fresh"), store.listProfileNames())
    }

    @Test
    fun `touchNamedProfile does not overwrite an existing file`() = withTempDir { root ->
        val store = MemoryStore(root)
        store.addNamedProfileItem("a", ProfileSection.STYLE, "keep me")
        store.touchNamedProfile("a")
        assertEquals(listOf("keep me"), assertNotNull(store.loadNamedProfile("a")).style)
    }

    @Test
    fun `addRule numbers entries starting from 001`() = withTempDir { root ->
        val store = MemoryStore(root)
        val a = store.addRule("Only Kotlin")
        val b = store.addRule("No Spring")
        assertEquals("001", a.id)
        assertEquals("002", b.id)
    }

    @Test
    fun `listRules returns entries in id order`() = withTempDir { root ->
        val store = MemoryStore(root)
        store.addRule("First")
        store.addRule("Second")
        store.addRule("Third")
        val ids = store.listRules().map { it.id }
        assertEquals(listOf("001", "002", "003"), ids)
    }

    @Test
    fun `removeRule deletes the file and does not reuse the id`() = withTempDir { root ->
        val store = MemoryStore(root)
        store.addRule("first")
        store.addRule("second")
        assertTrue(store.removeRule("001"))
        val third = store.addRule("third")
        assertEquals("003", third.id, "deleted ids must not be recycled")
        val remaining = store.listRules().map { it.id }
        assertEquals(listOf("002", "003"), remaining)
    }

    @Test
    fun `removeRule on a missing id returns false`() = withTempDir { root ->
        val store = MemoryStore(root)
        store.addRule("only one")
        assertFalse(store.removeRule("999"))
    }

    @Test
    fun `listRules ignores files not matching the NNN-slug shape`() = withTempDir { root ->
        val store = MemoryStore(root)
        store.addRule("valid")
        File(root, MemoryStore.RULES_DIR).resolve("notes.md").writeText("stray")
        File(root, MemoryStore.RULES_DIR).resolve("README").writeText("ignore me")
        assertEquals(listOf("001"), store.listRules().map { it.id })
    }

    @Test
    fun `slugify replaces non-ascii alphanum with dash`() {
        assertEquals("kotlin-only", MemoryStore.slugify("Kotlin only"))
        assertEquals("a-b-c", MemoryStore.slugify("a! b? c"))
        assertEquals("", MemoryStore.slugify("кириллица"))
    }

    @Test
    fun `addRule with pure cyrillic text falls back to rule slug`() = withTempDir { root ->
        val store = MemoryStore(root)
        store.addRule("Запрещено всё")
        val files = File(root, MemoryStore.RULES_DIR).listFiles()!!.map { it.name }
        assertEquals(listOf("001-rule.md"), files)
    }

    @Test
    fun `saveTask and loadTask round-trip with all sections`() = withTempDir { root ->
        val store = MemoryStore(root)
        val notes = TaskNotes(
            taskId = "auth-service",
            goal = "Сервис авторизации поверх Ktor + JWT",
            stage = "planning",
            notes = listOf("Уже выбран стек: Ktor 3", "Не использовать Spring"),
        )
        store.saveTask(notes)
        val loaded = store.loadTask("auth-service")
        assertEquals(notes, loaded)
    }

    @Test
    fun `loadTask returns null when no file exists`() = withTempDir { root ->
        val store = MemoryStore(root)
        assertNull(store.loadTask("missing"))
    }

    @Test
    fun `parseTaskNotes on a header-only file returns empty fields`() {
        val notes = MemoryStore.parseTaskNotes("foo", "# Task: foo\n")
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
        val notes = MemoryStore.parseTaskNotes("foo", raw)
        assertEquals("G", notes.goal)
        assertNull(notes.stage)
        assertEquals(listOf("n1"), notes.notes)
    }

    @Test
    fun `appendTaskNote creates the file when missing`() = withTempDir { root ->
        val store = MemoryStore(root)
        store.appendTaskNote("fresh", "first note")
        val loaded = assertNotNull(store.loadTask("fresh"))
        assertEquals(listOf("first note"), loaded.notes)
    }

    @Test
    fun `appendTaskNote keeps prior notes and appends a new one`() = withTempDir { root ->
        val store = MemoryStore(root)
        store.saveTask(TaskNotes("auth", goal = "ship it", notes = listOf("n1")))
        store.appendTaskNote("auth", "n2")
        val loaded = assertNotNull(store.loadTask("auth"))
        assertEquals("ship it", loaded.goal)
        assertEquals(listOf("n1", "n2"), loaded.notes)
    }

    @Test
    fun `renderTaskNotes omits empty sections`() {
        val rendered = MemoryStore.renderTaskNotes(TaskNotes("t", goal = "g"))
        assertTrue(rendered.contains("## Goal"))
        assertFalse(rendered.contains("## Stage"))
        assertFalse(rendered.contains("## Notes"))
    }

    @Test
    fun `listTaskIds is sorted alphabetically and ignores non-md files`() = withTempDir { root ->
        val store = MemoryStore(root)
        store.saveTask(TaskNotes("zeta"))
        store.saveTask(TaskNotes("alpha"))
        store.saveTask(TaskNotes("middle"))
        File(root, MemoryStore.TASKS_DIR).resolve("README").writeText("ignore me")
        assertEquals(listOf("alpha", "middle", "zeta"), store.listTaskIds())
    }
}

/**
 * Run [block] against a fresh tmp directory that is recursively deleted
 * afterward. Local helper so each test stays self-contained — no shared
 * state, parallel-test-safe.
 */
private inline fun withTempDir(block: (File) -> Unit) {
    val dir = Files.createTempDirectory("project01-memory-").toFile()
    try {
        block(dir)
    } finally {
        dir.deleteRecursively()
    }
}
