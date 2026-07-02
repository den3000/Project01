package ru.den.writes.code.agenticHub.mcps.localfs

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DocumentTest {

    @Test
    fun `when render an empty document - then an empty string`() {
        // when - then
        assertEquals("", renderDocument(emptyList()))
    }

    @Test
    fun `when render lines - then they are joined by newlines`() {
        // given
        val lines = listOf("Paris: rain, 14.0°C", "Tokyo: clear sky, 22.0°C")

        // when - then
        assertEquals("Paris: rain, 14.0°C\nTokyo: clear sky, 22.0°C", renderDocument(lines))
    }

    @Test
    fun `when filename is blank or missing - then default document md under documents dir`() {
        // when - then
        assertEquals("document.md", documentFileFor(null).name)
        assertEquals("document.md", documentFileFor("  ").name)
        assertEquals(documentsDir(), documentFileFor(null).parentFile)
    }

    @Test
    fun `when filename carries a path - then only the base name under documents dir`() {
        // when - then
        assertEquals(File(documentsDir(), "x.md"), documentFileFor("../../x.md"))
        assertEquals(File(documentsDir(), "b.md"), documentFileFor("a/b.md"))
    }

    @Test
    fun `when save to a fresh path - then it creates the dir and writes the content`() {
        // given — a nested path whose parent dir does not exist yet
        val tmp = File.createTempFile("document-test", "").apply { delete() }
        val file = File(tmp, "nested/document.md")

        try {
            // when
            saveDocument(file, "Paris: rain\nTokyo: clear sky")

            // then
            assertTrue(file.exists())
            assertEquals("Paris: rain\nTokyo: clear sky", file.readText())
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `when add lines - then size grows and snapshot reflects them`() = runBlocking {
        // given
        val store = DocumentStore()

        // when
        val first = store.add("Paris: rain")
        val second = store.add("Tokyo: clear sky")

        // then
        assertEquals(1, first)
        assertEquals(2, second)
        assertEquals(listOf("Paris: rain", "Tokyo: clear sky"), store.snapshot())
    }

    @Test
    fun `when render the store - then it matches renderDocument of the snapshot`() = runBlocking {
        // given
        val store = DocumentStore()
        store.add("Paris: rain")

        // when - then
        assertEquals(renderDocument(listOf("Paris: rain")), store.render())
    }
}
