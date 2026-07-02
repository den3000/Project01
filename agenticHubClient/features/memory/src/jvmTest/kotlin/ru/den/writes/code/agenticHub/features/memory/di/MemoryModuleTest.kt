package ru.den.writes.code.agenticHub.features.memory.di

import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import ru.den.writes.code.agenticHub.features.memory.MemoryMode
import ru.den.writes.code.agenticHub.features.memory.MemoryProvider
import ru.den.writes.code.agenticHub.features.memory.MemoryStore
import ru.den.writes.code.agenticHub.platform.filesystem.di.fileSystemModule
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNotNull

class MemoryModuleTest {

    @Test
    fun `when memoryModule loaded - then MemoryStore resolves with root param`() {
        // given
        val root = Files.createTempDirectory("mem-di").toString()
        val koin = koinApplication { modules(fileSystemModule, memoryModule) }.koin

        // when
        val store = koin.get<MemoryStore> { parametersOf(root) }

        // then
        assertNotNull(store)
    }

    @Test
    fun `when MemoryProvider resolved - then it builds over the store`() {
        // given
        val root = Files.createTempDirectory("mem-di").toString()
        val koin = koinApplication { modules(fileSystemModule, memoryModule) }.koin

        // when
        val provider = koin.get<MemoryProvider> { parametersOf(root, MemoryMode.PREAMBLE, null, null) }

        // then
        assertNotNull(provider)
    }
}
