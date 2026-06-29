package ru.den.writes.code.project01.mcps.openmeteo

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReportTest {

    @Test
    fun `when render an empty report - then header plus placeholder`() {
        // when - then
        assertEquals("# Weather report\n\n(empty report)", renderReport(emptyList()))
    }

    @Test
    fun `when render entries - then header plus one bullet each`() {
        // given
        val entries = listOf("Paris: rain, 14.0°C", "Tokyo: clear sky, 22.0°C")

        // when
        val actual = renderReport(entries)

        // then
        assertEquals(
            "# Weather report\n\n- Paris: rain, 14.0°C\n- Tokyo: clear sky, 22.0°C",
            actual,
        )
    }

    @Test
    fun `when filename is blank or missing - then default report md under reports dir`() {
        // when - then
        assertEquals("report.md", reportFileFor(null).name)
        assertEquals("report.md", reportFileFor("  ").name)
        assertEquals(reportsDir(), reportFileFor(null).parentFile)
    }

    @Test
    fun `when filename carries a path - then only the base name under reports dir`() {
        // when - then
        assertEquals(File(reportsDir(), "x.md"), reportFileFor("../../x.md"))
        assertEquals(File(reportsDir(), "b.md"), reportFileFor("a/b.md"))
    }

    @Test
    fun `when save to a fresh path - then it creates the dir and writes the content`() {
        // given — a nested path whose parent dir does not exist yet
        val tmp = File.createTempFile("report-test", "").apply { delete() }
        val file = File(tmp, "nested/report.md")

        try {
            // when
            saveReport(file, "# Weather report\n\n- Paris: rain")

            // then
            assertTrue(file.exists())
            assertEquals("# Weather report\n\n- Paris: rain", file.readText())
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `when add entries - then size grows and snapshot reflects them`() = runBlocking {
        // given
        val store = ReportStore()

        // when
        val first = store.add("Paris: rain")
        val second = store.add("Tokyo: clear sky")

        // then
        assertEquals(1, first)
        assertEquals(2, second)
        assertEquals(listOf("Paris: rain", "Tokyo: clear sky"), store.snapshot())
    }

    @Test
    fun `when render the store - then it matches renderReport of the snapshot`() = runBlocking {
        // given
        val store = ReportStore()
        store.add("Paris: rain")

        // when
        val rendered = store.render()

        // then
        assertEquals(renderReport(listOf("Paris: rain")), rendered)
    }
}
