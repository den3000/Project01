package ru.den.writes.code.project01.cliJvm.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileTest {

    @Test
    fun `parseProfileData on blank input returns empty data`() {
        assertTrue(parseProfileData("").isEmpty())
        assertTrue(parseProfileData("   \n  ").isEmpty())
    }

    @Test
    fun `parseProfileData on pure free text keeps it as freeText only`() {
        val data = parseProfileData("Я пишу только на Kotlin\nи использую KMP")
        assertEquals("Я пишу только на Kotlin\nи использую KMP", data.freeText)
        assertEquals(emptyList(), data.style)
        assertEquals(emptyList(), data.format)
        assertEquals(emptyList(), data.constraints)
        assertEquals(emptyList(), data.context)
    }

    @Test
    fun `parseProfileData on pure structured markdown fills typed fields`() {
        val raw = """
            ## Style
            - кратко
            - русский

            ## Format
            - code-first

            ## Constraints
            - Kotlin, KMP

            ## Context
            - Android-разработчик
        """.trimIndent()
        val data = parseProfileData(raw)
        assertNull(data.freeText)
        assertEquals(listOf("кратко", "русский"), data.style)
        assertEquals(listOf("code-first"), data.format)
        assertEquals(listOf("Kotlin, KMP"), data.constraints)
        assertEquals(listOf("Android-разработчик"), data.context)
    }

    @Test
    fun `parseProfileData mixes leading freeText with sections`() {
        val raw = """
            Hello, I'm a senior Android dev.

            ## Style
            - кратко
        """.trimIndent()
        val data = parseProfileData(raw)
        assertEquals("Hello, I'm a senior Android dev.", data.freeText)
        assertEquals(listOf("кратко"), data.style)
    }

    @Test
    fun `parseProfileData skips empty sections and keeps later ones`() {
        val raw = """
            ## Style

            ## Format
            - markdown
        """.trimIndent()
        val data = parseProfileData(raw)
        assertEquals(emptyList(), data.style)
        assertEquals(listOf("markdown"), data.format)
    }

    @Test
    fun `parseProfileData accepts mixed bullets and bare lines`() {
        val raw = """
            ## Style
            - dash
            * asterisk
            bare
        """.trimIndent()
        val data = parseProfileData(raw)
        assertEquals(listOf("dash", "asterisk", "bare"), data.style)
    }

    @Test
    fun `parseProfileData is case-insensitive on section headers`() {
        val raw = """
            ## STYLE
            - a
            ## format
            - b
            ## Constraints
            - c
            ## context
            - d
        """.trimIndent()
        val data = parseProfileData(raw)
        assertEquals(listOf("a"), data.style)
        assertEquals(listOf("b"), data.format)
        assertEquals(listOf("c"), data.constraints)
        assertEquals(listOf("d"), data.context)
    }

    @Test
    fun `parseProfileData silently drops unknown sections`() {
        val raw = """
            ## Style
            - keep me
            ## SomethingElse
            - dropped
            ## Context
            - also keep
        """.trimIndent()
        val data = parseProfileData(raw)
        assertEquals(listOf("keep me"), data.style)
        assertEquals(listOf("also keep"), data.context)
        assertEquals(emptyList(), data.format)
    }

    @Test
    fun `renderProfileData on empty returns blank`() {
        assertEquals("", renderProfileData(ProfileData()))
    }

    @Test
    fun `renderProfileData with only freeText omits section headings`() {
        val rendered = renderProfileData(ProfileData(freeText = "just text"))
        assertEquals("just text", rendered)
    }

    @Test
    fun `renderProfileData emits sections in fixed order`() {
        val rendered = renderProfileData(
            ProfileData(
                context = listOf("c1"),
                style = listOf("s1"),
                format = listOf("f1"),
                constraints = listOf("k1"),
            )
        )
        val expected = """
            ## Style
            - s1

            ## Format
            - f1

            ## Constraints
            - k1

            ## Context
            - c1
        """.trimIndent()
        assertEquals(expected, rendered)
    }

    @Test
    fun `parse and render round-trip preserves data`() {
        val original = ProfileData(
            style = listOf("кратко", "русский"),
            format = listOf("code-first"),
            constraints = listOf("Kotlin", "no RxJava"),
            context = listOf("Android dev"),
            freeText = "leading prose",
        )
        val rendered = renderProfileData(original)
        val parsed = parseProfileData(rendered)
        assertEquals(original, parsed)
    }

    @Test
    fun `addItem appends to the section and ignores blank text`() {
        val empty = ProfileData()
        val once = empty.addItem(ProfileSection.STYLE, "a")
        val twice = once.addItem(ProfileSection.STYLE, "  b ")
        val ignored = twice.addItem(ProfileSection.STYLE, "   ")
        assertEquals(listOf("a", "b"), twice.style)
        assertEquals(twice, ignored)
    }

    @Test
    fun `clear empties only the requested section`() {
        val full = ProfileData(
            style = listOf("a"),
            format = listOf("b"),
            constraints = listOf("c"),
            context = listOf("d"),
        )
        val cleared = full.clear(ProfileSection.STYLE)
        assertEquals(emptyList(), cleared.style)
        assertEquals(listOf("b"), cleared.format)
        assertEquals(listOf("c"), cleared.constraints)
        assertEquals(listOf("d"), cleared.context)
    }

    @Test
    fun `isEmpty is true only when every section and freeText are blank`() {
        assertTrue(ProfileData().isEmpty())
        assertTrue(ProfileData(freeText = "   ").isEmpty())
        assertFalse(ProfileData(freeText = "x").isEmpty())
        assertFalse(ProfileData(style = listOf("a")).isEmpty())
    }

    @Test
    fun `ProfileSection byKeyword is case-insensitive and rejects unknown`() {
        assertEquals(ProfileSection.STYLE, ProfileSection.byKeyword("style"))
        assertEquals(ProfileSection.CONSTRAINTS, ProfileSection.byKeyword("CONSTRAINTS"))
        assertNull(ProfileSection.byKeyword("misc"))
    }
}
