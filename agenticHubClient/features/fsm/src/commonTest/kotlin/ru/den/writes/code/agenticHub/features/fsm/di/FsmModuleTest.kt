package ru.den.writes.code.agenticHub.features.fsm.di

import org.koin.dsl.koinApplication
import ru.den.writes.code.agenticHub.features.fsm.TaskStateMachine
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class FsmModuleTest {

    @Test
    fun `when fsmModule loaded - then TaskStateMachine resolves without other modules`() {
        // given
        val koin = koinApplication { modules(fsmModule) }.koin

        // when
        val machine = koin.get<TaskStateMachine>()

        // then
        assertNotNull(machine)
    }

    @Test
    fun `when TaskStateMachine resolved twice - then it is the same instance`() {
        // given
        val koin = koinApplication { modules(fsmModule) }.koin

        // when
        val first = koin.get<TaskStateMachine>()
        val second = koin.get<TaskStateMachine>()

        // then
        assertSame(first, second)
    }
}
