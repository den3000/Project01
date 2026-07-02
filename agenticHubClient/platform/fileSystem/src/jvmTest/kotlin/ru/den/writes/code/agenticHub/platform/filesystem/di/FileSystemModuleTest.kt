package ru.den.writes.code.agenticHub.platform.filesystem.di

import org.koin.dsl.koinApplication
import ru.den.writes.code.agenticHub.platform.filesystem.LocalFileSystem
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class FileSystemModuleTest {

    @Test
    fun `when fileSystemModule loaded - then LocalFileSystem resolves`() {
        // given
        val koin = koinApplication { modules(fileSystemModule) }.koin

        // when
        val fs = koin.get<LocalFileSystem>()

        // then
        assertNotNull(fs)
    }

    @Test
    fun `when resolved twice - then single returns same instance`() {
        // given
        val koin = koinApplication { modules(fileSystemModule) }.koin

        // when
        val first = koin.get<LocalFileSystem>()
        val second = koin.get<LocalFileSystem>()

        // then
        assertSame(first, second)
    }
}
