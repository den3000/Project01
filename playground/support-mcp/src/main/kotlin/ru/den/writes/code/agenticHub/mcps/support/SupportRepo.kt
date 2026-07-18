package ru.den.writes.code.agenticHub.mcps.support

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Reads one named text resource — the impure edge of the server. The Loader factoring
 * keeps [SupportRepo] free of file I/O so its tools/arg-building/formatting are unit-tested
 * with a fake loader. Production impl is [FileLoader].
 */
fun interface Loader {
    fun read(name: String): String
}

@Serializable
data class SupportUser(
    val id: String,
    val name: String,
    val email: String,
    val tariff: String,
    val product: String,
    val since: String,
)

@Serializable
data class TicketComment(
    val author: String,
    val at: String,
    val text: String,
)

@Serializable
data class SupportTicket(
    val id: String,
    val subject: String,
    val description: String,
    val status: String,
    val priority: String,
    val createdAt: String,
    val updatedAt: String,
    val customerId: String,
    val comments: List<TicketComment> = emptyList(),
)

/**
 * Read-only surface over a users/tickets fixture, backing the MCP tools. Every call hits
 * [loader] again (no cache) so an operator can edit the JSON files between calls without
 * restarting the server — the fixture is small enough that the parse cost is invisible.
 * Never mutates: the demo doesn't need write-back and the extra surface would need
 * concurrency guards we don't have.
 */
class SupportRepo(private val loader: Loader) {

    /** Every ticket, id + subject + status + priority + customerId, sorted by updatedAt desc. */
    fun listTickets(): String {
        val tickets = readTickets().sortedByDescending { it.updatedAt }
        if (tickets.isEmpty()) return "(no tickets)"
        return tickets.joinToString("\n") { formatTicketSummary(it) }
    }

    /** Full ticket record, with all comments; a clear notice when the id is unknown. */
    fun getTicket(id: String): String {
        val ticket = readTickets().firstOrNull { it.id == id }
            ?: return "(no ticket $id)"
        return formatTicketFull(ticket)
    }

    /**
     * Tickets whose subject OR description contains [query] as a case-insensitive
     * substring. Empty query returns the same summary as [listTickets] would.
     */
    fun searchTickets(query: String): String {
        val q = query.trim()
        val tickets = readTickets().let { all ->
            if (q.isEmpty()) all else all.filter {
                it.subject.contains(q, ignoreCase = true) ||
                    it.description.contains(q, ignoreCase = true)
            }
        }.sortedByDescending { it.updatedAt }
        if (tickets.isEmpty()) return "(no tickets match '$query')"
        return tickets.joinToString("\n") { formatTicketSummary(it) }
    }

    /** Full user record; a clear notice when the id is unknown. */
    fun getUser(id: String): String {
        val user = readUsers().firstOrNull { it.id == id }
            ?: return "(no user $id)"
        return formatUserFull(user)
    }

    private fun readTickets(): List<SupportTicket> =
        JSON.decodeFromString(TICKETS_SERIALIZER, loader.read(TICKETS_FILE))

    private fun readUsers(): List<SupportUser> =
        JSON.decodeFromString(USERS_SERIALIZER, loader.read(USERS_FILE))

    private fun formatTicketSummary(t: SupportTicket): String =
        "${t.id} [${t.status}, ${t.priority}] ${t.subject} (customer ${t.customerId})"

    private fun formatTicketFull(t: SupportTicket): String = buildString {
        appendLine("Ticket ${t.id}")
        appendLine("Subject: ${t.subject}")
        appendLine("Status: ${t.status}")
        appendLine("Priority: ${t.priority}")
        appendLine("Customer: ${t.customerId}")
        appendLine("Created: ${t.createdAt}")
        appendLine("Updated: ${t.updatedAt}")
        appendLine()
        appendLine("Description:")
        appendLine(t.description)
        if (t.comments.isNotEmpty()) {
            appendLine()
            appendLine("Comments:")
            t.comments.forEach { c ->
                appendLine("- [${c.at}] ${c.author}: ${c.text}")
            }
        }
    }.trimEnd()

    private fun formatUserFull(u: SupportUser): String = buildString {
        appendLine("User ${u.id}")
        appendLine("Name: ${u.name}")
        appendLine("Email: ${u.email}")
        appendLine("Tariff: ${u.tariff}")
        appendLine("Product: ${u.product}")
        appendLine("Since: ${u.since}")
    }.trimEnd()

    companion object {
        const val TICKETS_FILE = "tickets.json"
        const val USERS_FILE = "users.json"
        private val JSON = Json { ignoreUnknownKeys = true }
        private val TICKETS_SERIALIZER = kotlinx.serialization.builtins.ListSerializer(SupportTicket.serializer())
        private val USERS_SERIALIZER = kotlinx.serialization.builtins.ListSerializer(SupportUser.serializer())
    }
}

/**
 * Production [Loader]: reads `<root>/<name>` from disk on every call. The impure edge —
 * unwrapped so tests can swap it for an in-memory map.
 */
class FileLoader(private val root: String) : Loader {
    override fun read(name: String): String =
        java.io.File(root, name).readText(Charsets.UTF_8)
}
