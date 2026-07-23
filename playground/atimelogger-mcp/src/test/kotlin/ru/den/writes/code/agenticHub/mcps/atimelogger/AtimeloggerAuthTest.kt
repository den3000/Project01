package ru.den.writes.code.agenticHub.mcps.atimelogger

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AtimeloggerAuthTest {

    @Test
    fun `when basicAuthHeader for user and password - then Basic scheme decoding to user colon password`() {
        // given
        val user = "alice"
        val password = "s3cret"

        // when
        val actual = basicAuthHeader(user, password)

        // then
        assertTrue(actual.startsWith("Basic "), "wrong scheme: $actual")
        val decoded = String(Base64.getDecoder().decode(actual.removePrefix("Basic ")), Charsets.UTF_8)
        assertEquals("alice:s3cret", decoded)
    }
}
