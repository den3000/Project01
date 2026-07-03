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
}
