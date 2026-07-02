package ru.den.writes.code.agenticHub.platform.greeting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GreetingTest {

    @Test
    fun `when sayHello given a name - then it wraps the name in a hello`() {
        // when
        val result = sayHello("World")

        // then
        assertEquals("Hello, World!", result)
    }

    @Test
    fun `when greet on the current platform - then it starts with a hello`() {
        // when
        val result = Greeting().greet()

        // then
        assertTrue(result.startsWith("Hello, "), "expected a hello, got: $result")
    }
}
