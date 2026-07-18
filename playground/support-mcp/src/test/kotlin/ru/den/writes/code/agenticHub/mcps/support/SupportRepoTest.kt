package ru.den.writes.code.agenticHub.mcps.support

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** In-memory map of file name → JSON string; no real file I/O. */
private class FakeLoader(private val files: Map<String, String>) : Loader {
    var lastName: String? = null
        private set

    override fun read(name: String): String {
        lastName = name
        return files[name] ?: error("no fixture for '$name'")
    }
}

private const val TICKETS_JSON = """[
    {
        "id": "TICKET-4412",
        "subject": "Не вижу задачи после установки на Аврору",
        "description": "На эмуляторе всё было, на устройстве Аврора список пустой.",
        "status": "open",
        "priority": "high",
        "createdAt": "2026-07-10T09:12:00Z",
        "updatedAt": "2026-07-16T14:20:00Z",
        "customerId": "USER-102",
        "comments": [
            {"author": "USER-102", "at": "2026-07-10T09:12:00Z", "text": "не помогает пересборка"}
        ]
    },
    {
        "id": "TICKET-4420",
        "subject": "Задача не обновляется",
        "description": "Меняю title, шлю POST — на сервере остаётся старое.",
        "status": "pending",
        "priority": "normal",
        "createdAt": "2026-07-14T10:00:00Z",
        "updatedAt": "2026-07-15T09:00:00Z",
        "customerId": "USER-104",
        "comments": []
    }
]"""

private const val USERS_JSON = """[
    {
        "id": "USER-102",
        "name": "Иван Петров",
        "email": "ivan@example.org",
        "tariff": "team",
        "product": "CTT",
        "since": "2024-11-01"
    }
]"""

class SupportRepoTest {

    //region get_ticket
    @Test
    fun `when ticket exists - then getTicket returns full record with header and description`() {
        // given
        val loader = FakeLoader(mapOf("tickets.json" to TICKETS_JSON))

        // when
        val text = SupportRepo(loader).getTicket("TICKET-4412")

        // then
        assertEquals("tickets.json", loader.lastName)
        assertTrue(text.startsWith("Ticket TICKET-4412\n"), "header missing: $text")
        assertTrue("Subject: Не вижу задачи" in text)
        assertTrue("Status: open" in text)
        assertTrue("Priority: high" in text)
        assertTrue("Customer: USER-102" in text)
        assertTrue("Description:" in text)
        assertTrue("Comments:" in text)
        assertTrue("USER-102: не помогает пересборка" in text)
    }

    @Test
    fun `when ticket has no comments - then Comments section is omitted`() {
        // given
        val loader = FakeLoader(mapOf("tickets.json" to TICKETS_JSON))

        // when
        val text = SupportRepo(loader).getTicket("TICKET-4420")

        // then
        assertTrue("Comments:" !in text, "Comments section leaked: $text")
    }

    @Test
    fun `when ticket id is unknown - then getTicket returns a clear notice`() {
        // given
        val loader = FakeLoader(mapOf("tickets.json" to TICKETS_JSON))

        // when - then
        assertEquals("(no ticket TICKET-9999)", SupportRepo(loader).getTicket("TICKET-9999"))
    }
    //endregion

    //region list_tickets
    @Test
    fun `when tickets present - then listTickets returns one summary line per ticket sorted by updatedAt desc`() {
        // given — TICKET-4412 updated 2026-07-16, TICKET-4420 updated 2026-07-15
        val loader = FakeLoader(mapOf("tickets.json" to TICKETS_JSON))

        // when
        val text = SupportRepo(loader).listTickets()

        // then
        val lines = text.lines()
        assertEquals(2, lines.size, "expected 2 lines, got: $text")
        assertTrue(lines[0].startsWith("TICKET-4412 [open, high]"), "wrong order: $text")
        assertTrue(lines[1].startsWith("TICKET-4420 [pending, normal]"), "wrong order: $text")
        assertTrue("(customer USER-102)" in lines[0])
    }

    @Test
    fun `when tickets file is an empty array - then listTickets returns a clear notice`() {
        // given
        val loader = FakeLoader(mapOf("tickets.json" to "[]"))

        // when - then
        assertEquals("(no tickets)", SupportRepo(loader).listTickets())
    }
    //endregion

    //region search_tickets
    @Test
    fun `when query matches a subject substring - then searchTickets returns that summary`() {
        // given
        val loader = FakeLoader(mapOf("tickets.json" to TICKETS_JSON))

        // when
        val text = SupportRepo(loader).searchTickets("аврору")

        // then — case-insensitive, matches subject
        val lines = text.lines()
        assertEquals(1, lines.size, "expected one match: $text")
        assertTrue(lines[0].startsWith("TICKET-4412"), "wrong match: $text")
    }

    @Test
    fun `when query matches a description substring - then searchTickets returns it`() {
        // given
        val loader = FakeLoader(mapOf("tickets.json" to TICKETS_JSON))

        // when
        val text = SupportRepo(loader).searchTickets("POST")

        // then
        assertTrue(text.startsWith("TICKET-4420"), "wrong match: $text")
    }

    @Test
    fun `when nothing matches - then searchTickets returns a clear notice with the query`() {
        // given
        val loader = FakeLoader(mapOf("tickets.json" to TICKETS_JSON))

        // when - then
        assertEquals(
            "(no tickets match 'квантовая гравитация')",
            SupportRepo(loader).searchTickets("квантовая гравитация"),
        )
    }
    //endregion

    //region get_user
    @Test
    fun `when user exists - then getUser returns full record`() {
        // given
        val loader = FakeLoader(mapOf("users.json" to USERS_JSON))

        // when
        val text = SupportRepo(loader).getUser("USER-102")

        // then
        assertEquals("users.json", loader.lastName)
        assertTrue(text.startsWith("User USER-102\n"), "header missing: $text")
        assertTrue("Name: Иван Петров" in text)
        assertTrue("Email: ivan@example.org" in text)
        assertTrue("Tariff: team" in text)
        assertTrue("Product: CTT" in text)
        assertTrue("Since: 2024-11-01" in text)
    }

    @Test
    fun `when user id is unknown - then getUser returns a clear notice`() {
        // given
        val loader = FakeLoader(mapOf("users.json" to USERS_JSON))

        // when - then
        assertEquals("(no user USER-999)", SupportRepo(loader).getUser("USER-999"))
    }
    //endregion

    //region parsing tolerance
    @Test
    fun `when tickets JSON carries an unknown field - then parsing ignores it`() {
        // given — kotlinx-serialization is configured with ignoreUnknownKeys
        val withExtra = """[
            {"id":"TICKET-1","subject":"s","description":"d","status":"open",
             "priority":"low","createdAt":"2026-01-01","updatedAt":"2026-01-01",
             "customerId":"USER-1","extraFieldFromFuture":"ignore me"}
        ]"""
        val loader = FakeLoader(mapOf("tickets.json" to withExtra))

        // when - then
        assertTrue(SupportRepo(loader).getTicket("TICKET-1").startsWith("Ticket TICKET-1"))
    }
    //endregion
}
