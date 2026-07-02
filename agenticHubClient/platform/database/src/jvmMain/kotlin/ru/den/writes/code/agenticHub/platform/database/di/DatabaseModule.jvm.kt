package ru.den.writes.code.agenticHub.platform.database.di

import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.dsl.onClose
import ru.den.writes.code.agenticHub.platform.database.AppDatabase
import ru.den.writes.code.agenticHub.platform.database.MessageDao
import ru.den.writes.code.agenticHub.platform.database.buildDatabase

// AppDatabase spans the whole process → single; closed by Koin on stopKoin()
// via onClose. MessageDao is a thin accessor off it.
internal actual fun databaseModule(): Module = module {
    single<AppDatabase> { buildDatabase() } onClose { it?.close() }
    single<MessageDao> { get<AppDatabase>().messageDao() }
}
