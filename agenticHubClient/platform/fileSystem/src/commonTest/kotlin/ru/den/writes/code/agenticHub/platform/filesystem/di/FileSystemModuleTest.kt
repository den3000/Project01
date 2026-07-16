package ru.den.writes.code.agenticHub.platform.filesystem.di

import ru.den.writes.code.agenticHub.testutils.IgnoreIos
import org.koin.dsl.koinApplication
import ru.den.writes.code.agenticHub.platform.filesystem.LocalFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// @IgnoreIos: fileSystemModule (eager val в этом же файле) на iOS = TODO(), Native
// инициализирует все top-level val файла разом → тест упал бы при инициализации.
// Помечаем как ignored на iOS (видно в репорте: iOS-реализация не готова), на JVM гоняется.
@IgnoreIos
class FileSystemModuleTest {

    // Один граф на весь класс: fileSystemTestModule на factory, каждый get() —
    // свежий LocalFileSystemFake, так что тесты независимы без пересборки koin.
    private val koin = koinApplication { modules(fileSystemTestModule) }.koin

    @Test
    fun `when fileSystemModule loaded - then LocalFileSystem resolves`() {
        // when
        val fs = koin.get<LocalFileSystem>()

        // then
        assertNotNull(fs)
    }

    @Test
    fun `when resolved twice - then module returns different instances`() {
        // when
        val first = koin.get<LocalFileSystem>()
        val second = koin.get<LocalFileSystem>()

        // then
        assertNotSame(first, second)
    }

    @Test
    fun `when fileSystemTestModule loaded - then LocalFileSystem round-trips in memory`() {
        // given
        val fs = koin.get<LocalFileSystem>()

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
        val fs = koin.get<LocalFileSystem>()
        fs.writeText("/root/x.md", "x")

        // when
        val removed = fs.delete("/root/x.md")

        // then
        assertTrue(removed)
        assertNull(fs.readText("/root/x.md"))
        assertEquals(emptyList(), fs.listFileNames("/root"))
    }

    @Test
    fun `when path holds files - then isDirectory is true`() {
        // given
        val fs = koin.get<LocalFileSystem>()
        fs.writeText("/repo/README.md", "root")

        // then
        assertTrue(fs.isDirectory("/repo"))
    }

    @Test
    fun `when path is a regular file - then isDirectory is false`() {
        // given
        val fs = koin.get<LocalFileSystem>()
        fs.writeText("/repo/README.md", "root")

        // then
        assertFalse(fs.isDirectory("/repo/README.md"))
    }

    @Test
    fun `when path is absent - then isDirectory is false`() {
        // given
        val fs = koin.get<LocalFileSystem>()

        // then
        assertFalse(fs.isDirectory("/nope"))
    }

    @Test
    fun `when walkFiles on nested tree - then returns relative paths recursively`() {
        // given
        val fs = koin.get<LocalFileSystem>()
        fs.writeText("/repo/README.md", "root")
        fs.writeText("/repo/docs/a.md", "a")
        fs.writeText("/repo/docs/sub/b.md", "b")

        // when
        val files = fs.walkFiles("/repo").sorted()

        // then
        assertEquals(listOf("README.md", "docs/a.md", "docs/sub/b.md"), files)
    }

    @Test
    fun `when walkFiles on absent dir - then empty`() {
        // given
        val fs = koin.get<LocalFileSystem>()

        // then
        assertEquals(emptyList(), fs.walkFiles("/nope"))
    }
}
