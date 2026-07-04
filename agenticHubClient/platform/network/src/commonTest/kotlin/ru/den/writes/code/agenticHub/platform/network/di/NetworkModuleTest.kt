package ru.den.writes.code.agenticHub.platform.network.di

import io.ktor.client.HttpClient
import org.koin.dsl.koinApplication
import ru.den.writes.code.agenticHub.testutils.IgnoreIos
import kotlin.test.Test
import kotlin.test.assertNotNull

// @IgnoreIos: networkModule (eager val в этом же файле) на iOS = TODO(), Native
// инициализирует все top-level val файла разом → тест упал бы при инициализации.
// На iOS — ignored (движок не готов), на JVM гоняется с реальным Java-клиентом.
@IgnoreIos
class NetworkModuleTest {

    @Test
    fun `when networkModule loaded - then HttpClient resolves`() {
        // given
        val app = koinApplication { modules(networkModule) }

        // when
        val client = app.koin.get<HttpClient>()

        // then
        assertNotNull(client)
        app.close()
    }
}
