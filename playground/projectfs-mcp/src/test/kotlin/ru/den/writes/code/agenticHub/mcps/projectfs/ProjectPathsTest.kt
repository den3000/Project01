package ru.den.writes.code.agenticHub.mcps.projectfs

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProjectPathsTest {

    //region containment
    @Test
    fun `when a relative path inside the root is resolved - then it is accepted with both forms`() {
        // given
        val paths = projectPaths()

        // when
        val actual = paths.resolveRead("docs/README.md")

        // then
        val expected = ProjectPaths.Resolved.Ok(rel = "docs/README.md", absolute = "/repo/docs/README.md")
        assertEquals(expected, actual)
    }

    @Test
    fun `when an absolute path inside the root is resolved - then it is rebased onto the root`() {
        // given
        val paths = projectPaths()

        // when
        val actual = paths.resolveRead("/repo/docs/README.md")

        // then
        val expected = ProjectPaths.Resolved.Ok(rel = "docs/README.md", absolute = "/repo/docs/README.md")
        assertEquals(expected, actual)
    }

    @Test
    fun `when a path escapes the root through dot-dot - then it is denied`() {
        // given
        val paths = projectPaths()

        // when
        val actual = paths.resolveRead("docs/../../secrets.txt")

        // then
        assertIs<ProjectPaths.Resolved.Denied>(actual)
        assertContains(actual.reason, "вне корня проекта")
    }

    @Test
    fun `when a symlink points outside the root - then it is denied`() {
        // given
        val paths = projectPaths(links = mapOf("/repo/outside" to "/elsewhere"))

        // when
        val actual = paths.resolveRead("outside/secrets.txt")

        // then
        assertIs<ProjectPaths.Resolved.Denied>(actual)
        assertContains(actual.reason, "вне корня проекта")
    }

    @Test
    fun `when a sibling directory shares the root prefix - then it is denied`() {
        // given
        val paths = projectPaths()

        // when
        val actual = paths.resolveRead("/repo-evil/plans.md")

        // then
        assertIs<ProjectPaths.Resolved.Denied>(actual)
        assertContains(actual.reason, "вне корня проекта")
    }

    @Test
    fun `when the root itself is resolved - then it is denied as not a file`() {
        // given
        val paths = projectPaths()

        // when
        val actual = paths.resolveRead("/repo")

        // then
        assertIs<ProjectPaths.Resolved.Denied>(actual)
        assertContains(actual.reason, "это сам корень")
    }

    @Test
    fun `when a blank path is resolved - then it is denied`() {
        // given
        val paths = projectPaths()

        // when
        val actual = paths.resolveRead("   ")

        // then
        assertIs<ProjectPaths.Resolved.Denied>(actual)
        assertContains(actual.reason, "пустой путь")
    }

    @Test
    fun `when a relative path from walk is turned absolute - then it is joined to the root`() {
        // given
        val paths = projectPaths()

        // when
        val actual = paths.absoluteOf("docs/README.md")

        // then
        assertEquals("/repo/docs/README.md", actual)
    }
    //endregion

    //region closed paths
    @Test
    fun `when a path under dot-git is resolved - then it is closed`() {
        // given
        val paths = projectPaths()

        // when
        val actual = paths.resolveRead(".git/config")

        // then
        assertIs<ProjectPaths.Resolved.Denied>(actual)
        assertContains(actual.reason, "путь закрыт")
    }

    @Test
    fun `when a credential file is resolved - then every known name is closed`() {
        // given
        val paths = projectPaths()
        val names = listOf("local.properties", ".env", "id_rsa", "id_ed25519", "secring.gpg")

        // when - then
        names.forEach { name ->
            assertTrue(paths.isClosed("config/$name"), "имя '$name' должно быть закрыто")
            assertIs<ProjectPaths.Resolved.Denied>(paths.resolveRead("config/$name"), "resolveRead('$name')")
        }
    }

    @Test
    fun `when a credential store is resolved - then every known extension is closed`() {
        // given
        val paths = projectPaths()
        val extensions = listOf("jks", "keystore", "p12", "pem", "key")

        // when - then
        extensions.forEach { extension ->
            assertTrue(paths.isClosed("app/release.$extension"), "расширение '.$extension' должно быть закрыто")
        }
    }

    @Test
    fun `when an ordinary source path is checked - then it is not closed`() {
        // given
        val paths = projectPaths()

        // when
        val actual = paths.isClosed("server/src/Main.kt")

        // then
        assertFalse(actual)
    }
    //endregion

    //region write gate
    @Test
    fun `when a write targets a generated directory - then every noise segment is refused`() {
        // given
        val paths = projectPaths()

        // when - then
        NOISE_SEGMENTS.forEach { segment ->
            val actual = paths.resolveWrite("$segment/report.md")
            assertIs<ProjectPaths.Resolved.Denied>(actual, "запись под '$segment/'")
        }
    }

    @Test
    fun `when write extensions are set and the target does not match - then the write is refused`() {
        // given
        val paths = projectPaths(writeExtensions = setOf("md"))

        // when
        val actual = paths.resolveWrite("server/Main.kt")

        // then
        assertIs<ProjectPaths.Resolved.Denied>(actual)
        assertContains(actual.reason, "только для .md")
    }

    @Test
    fun `when write extensions are set and the target matches - then the write is allowed`() {
        // given
        val paths = projectPaths(writeExtensions = setOf("md"))

        // when
        val actual = paths.resolveWrite("docs/report.md")

        // then
        val expected = ProjectPaths.Resolved.Ok(rel = "docs/report.md", absolute = "/repo/docs/report.md")
        assertEquals(expected, actual)
    }

    @Test
    fun `when no write extensions are set - then any extension is allowed`() {
        // given
        val paths = projectPaths()

        // when
        val actual = paths.resolveWrite("server/Main.kt")

        // then
        val expected = ProjectPaths.Resolved.Ok(rel = "server/Main.kt", absolute = "/repo/server/Main.kt")
        assertEquals(expected, actual)
    }
    //endregion
}
