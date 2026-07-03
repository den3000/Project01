package ru.den.writes.code.agenticHub.platform.filesystem.di

import org.koin.dsl.koinApplication
import ru.den.writes.code.agenticHub.platform.filesystem.LocalFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FileSystemModuleTest {

    @Test
    fun `when fileSystemModule loaded - then LocalFileSystem resolves`() {
        // given
        val koin = koinApplication { modules(fileSystemTestModule) }.koin

        // when
        val fs = koin.get<LocalFileSystem>()

        // then
        assertNotNull(fs)
    }

    @Test
    fun `when resolved twice - then module returns different instances`() {
        // given
        val koin = koinApplication { modules(fileSystemTestModule) }.koin

        // when
        val first = koin.get<LocalFileSystem>()
        val second = koin.get<LocalFileSystem>()

        // then
        assertNotSame(first, second)
    }

    @Test
    fun `when fileSystemTestModule loaded - then LocalFileSystem round-trips in memory`() {
        // given
        val fs = koinApplication { modules(fileSystemModule) }.koin.get<LocalFileSystem>()

        // when
        fs.mkdirs("/root/rules")
        fs.writeText("/root/rules/001-a.md", "alpha")
        fs.writeText("/root/rules/002-b.md", "beta")

        // then
        assertEquals("alpha", fs.readText("/root/rules/001-a.md"))
        assertEquals(listOf("001-a.md", "002-b.md"), fs.listFileNames("/root/rules").sorted())
        assertTrue(fs.exists("/root/rules"))
    }

    @Test
    fun `when file deleted - then it is gone and not listed`() {
        // given
        val fs = koinApplication { modules(fileSystemModule) }.koin.get<LocalFileSystem>()
        fs.writeText("/root/x.md", "x")

        // when
        val removed = fs.delete("/root/x.md")

        // then
        assertTrue(removed)
        assertNull(fs.readText("/root/x.md"))
        assertEquals(emptyList(), fs.listFileNames("/root"))
    }
}
