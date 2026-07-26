package ru.den.writes.code.agenticHub.features.fsm.di

import org.koin.core.module.Module
import org.koin.dsl.module
import ru.den.writes.code.agenticHub.features.fsm.TaskStateMachine

/**
 * Koin module for the task FSM — the module's only way out.
 *
 * One binding: [TaskStateMachine] as a `single`, because it is stateless and
 * shared for the whole session (same rule as `LocalFileSystem` / `StartExecutor`).
 * Nothing here takes runtime parameters, so there is no `parametersOf` — the
 * machine is given the task on every call rather than built around one.
 *
 * The module has no graph dependencies: the FSM decides over its own value types
 * and pulls nothing from other modules.
 */
public val fsmModule: Module = module {
    single { TaskStateMachine() }
}
