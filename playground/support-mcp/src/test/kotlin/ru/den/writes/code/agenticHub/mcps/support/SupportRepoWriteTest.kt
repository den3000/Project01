package ru.den.writes.code.agenticHub.mcps.support

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** In-memory store that keeps writes so a later read sees them; no real file I/O. */
private class MemStore(files: Map<String, String>) : Store {
    private val files = files.toMutableMap()

    override fun read(name: String): String =
        files[name] ?: error("no fixture for '$name'")

    override fun write(name: String, text: String) {
        files[name] = text
    }
}

private const val NOW = "2026-07-18T12:00:00Z"

private const val USERS = """[
    {"id":"USER-102","name":"Иван Петров","email":"ivan@example.org","tariff":"team","product":"CTT","since":"2024-11-01"}
]"""

private const val TICKETS = """[
    {"id":"TICKET-4412","subject":"A","description":"a","status":"open","priority":"high",
     "createdAt":"2026-07-10","updatedAt":"2026-07-16","customerId":"USER-102"},
    {"id":"TICKET-4420","subject":"B","description":"b","status":"new","priority":"normal",
     "createdAt":"2026-07-14","updatedAt":"2026-07-15","customerId":"USER-102"}
]"""

private fun repo(vararg files: Pair<String, String>) =
    SupportRepo(MemStore(files.toMap())) { NOW }

class SupportRepoWriteTest {

    //region create_ticket
    @Test
    fun `when a registered customer escalates - then createTicket appends a new ticket visible to getTicket`() {
        // given — existing ids max out at 4420
        val r = repo("users.json" to USERS, "tickets.json" to TICKETS)

        // when
        val result = r.createTicket("USER-102", "Не грузится список", "Пустой экран после входа")

        // then — next id is max+1, status new, and it round-trips through the store
        assertEquals("Created TICKET-4421 (status new) for USER-102", result)
        val ticket = r.getTicket("TICKET-4421")
        assertTrue("Status: new" in ticket, ticket)
        assertTrue("Subject: Не грузится список" in ticket, ticket)
        assertTrue("Customer: USER-102" in ticket, ticket)
    }

    @Test
    fun `when the customer is not registered - then createTicket refuses and writes nothing`() {
        // given
        val r = repo("users.json" to USERS, "tickets.json" to TICKETS)

        // when
        val result = r.createTicket("USER-999", "x", "y")

        // then
        assertEquals("(no user USER-999)", result)
        assertEquals("(no tickets match 'x')", r.searchTickets("x"), "a ticket leaked into the store")
    }
    //endregion

    //region set_ticket_status
    @Test
    fun `when a developer resolves a ticket - then status, resolution and a note are persisted`() {
        // given
        val r = repo("users.json" to USERS, "tickets.json" to TICKETS)

        // when
        val result = r.setTicketStatus("TICKET-4412", "resolved", "Задайте SERVER_IP или используйте 10.0.2.2")

        // then
        assertEquals("Ticket TICKET-4412 → resolved", result)
        val ticket = r.getTicket("TICKET-4412")
        assertTrue("Status: resolved" in ticket, ticket)
        assertTrue("Resolution: Задайте SERVER_IP" in ticket, ticket)
        assertTrue("developer: status → resolved" in ticket, ticket)
    }

    @Test
    fun `when the status is not a known value - then setTicketStatus rejects it`() {
        // given
        val r = repo("users.json" to USERS, "tickets.json" to TICKETS)

        // when
        val result = r.setTicketStatus("TICKET-4412", "done", "whatever")

        // then
        assertTrue(result.startsWith("(invalid status 'done'"), result)
    }

    @Test
    fun `when the ticket id is unknown - then setTicketStatus returns a clear notice`() {
        // given
        val r = repo("users.json" to USERS, "tickets.json" to TICKETS)

        // when - then
        assertEquals("(no ticket TICKET-0)", r.setTicketStatus("TICKET-0", "resolved", "x"))
    }
    //endregion
}
