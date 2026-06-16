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
