package ru.den.writes.code.agenticHub.features.fsm.di

import org.koin.core.module.Module
import org.koin.dsl.module
import ru.den.writes.code.agenticHub.features.fsm.TaskStateMachine
import ru.den.writes.code.agenticHub.features.fsm.TaskStateMachineImpl

/**
 * Koin module for the task FSM — the module's only way out.
 *
 * One binding: [TaskStateMachine] as a `single`, resolved under the interface so
 * whoever asks the graph gets the one question it may ask. A `single` because the
 * machine is stateless and shared for the whole session (same rule as
 * `LocalFileSystem` / `StartExecutor`). Nothing here takes runtime parameters, so
 * there is no `parametersOf` — the machine is given the task on every call rather
 * than built around one.
 *
 * The module has no graph dependencies: the FSM decides over its own value types
 * and pulls nothing from other modules.
 */
public val fsmModule: Module = module {
    single<TaskStateMachine> { TaskStateMachineImpl() }
}
