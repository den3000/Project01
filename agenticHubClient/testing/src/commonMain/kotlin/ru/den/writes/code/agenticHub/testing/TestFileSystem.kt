package ru.den.writes.code.agenticHub.testing

import org.koin.dsl.koinApplication
import ru.den.writes.code.agenticHub.platform.filesystem.LocalFileSystem
import ru.den.writes.code.agenticHub.platform.filesystem.di.fileSystemModule

// Isolated Koin app (not the global startKoin) holding just the platform's
// fileSystemModule. Lazy so the module — TODO on non-JVM targets — is only built
// when a JVM test actually asks for a LocalFileSystem.
private val fsKoin by lazy { koinApplication { modules(fileSystemModule) }.koin }

/** The platform [LocalFileSystem], resolved from `fileSystemModule`, for tests. */
fun testLocalFileSystem(): LocalFileSystem = fsKoin.get()
