package ru.den.writes.code.agenticHub.features.lifecycle.start.di

import org.koin.core.module.Module
import org.koin.dsl.module
import ru.den.writes.code.agenticHub.features.lifecycle.start.StartExecutor

/**
 * Koin module for first-launch dispatch. [StartExecutor] holds the DB and the
 * file-system port (for admin memory ops), both from the graph → single.
 */
val startModule: Module = module {
    single { StartExecutor(db = get(), fs = get()) }
}
