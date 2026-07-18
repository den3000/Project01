package ru.den.writes.code.agenticHub.mcps.support

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Reads and writes one named text resource — the impure edge of the server. The Store
 * factoring keeps [SupportRepo] free of file I/O so its logic (lookups, formatting, ticket
 * creation and status changes) is unit-tested against an in-memory map. Production impl is
 * [FileStore]; reads and writes both hit `<root>/<name>`.
 */
interface Store {
    fun read(name: String): String
    fun write(name: String, text: String)
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
    val status: String = "new",
    val priority: String,
    val createdAt: String,
    val updatedAt: String,
    val customerId: String,
    val resolution: String? = null,
    val comments: List<TicketComment> = emptyList(),
)

/**
 * Surface over a users/tickets fixture, backing the MCP tools. Reads hit [store] again on
 * every call (no cache) so a hand-edit — or a write from a previous call — is visible
 * immediately; the fixture is small enough that the parse cost is invisible. Writes
 * (createTicket / setTicketStatus) rewrite the whole tickets file. Single-threaded by the
 * MCP tool loop, so no concurrency guard.
 *
 * [now] supplies the timestamp stamped onto new tickets/comments; injected so tests are
 * deterministic. Defaults to the wall clock.
 */
class SupportRepo(
    private val store: Store,
    private val now: () -> String = { java.time.Instant.now().toString() },
) {

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
     * substring — resolved ones included, so the assistant can reuse an existing solution.
     * Empty query returns the same summary as [listTickets] would.
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

    /**
     * Registered users whose name contains [name] as a case-insensitive substring — the
     * gate between "guest" and "registered" for the assistant. A blank query, or no match,
     * returns a clear notice so the assistant can refuse politely rather than invent a user.
     */
    fun findUser(name: String): String {
        val q = name.trim()
        if (q.isEmpty()) return "(no such user)"
        val matches = readUsers().filter { it.name.contains(q, ignoreCase = true) }
        if (matches.isEmpty()) return "(no such user matching '$name')"
        return matches.joinToString("\n") { formatUserSummary(it) }
    }

    /** Tickets belonging to one customer (id + status + subject), newest first; notice when none. */
    fun listUserTickets(customerId: String): String {
        val tickets = readTickets()
            .filter { it.customerId == customerId }
            .sortedByDescending { it.updatedAt }
        if (tickets.isEmpty()) return "(no tickets for $customerId)"
        return tickets.joinToString("\n") { formatTicketSummary(it) }
    }

    /**
     * Escalation: append a new `new` ticket for [customerId] and persist. The id is
     * `TICKET-<max+1>` over the existing numeric suffixes. Returns the created id (so the
     * assistant can quote it) or a notice when the customer is unknown.
     */
    fun createTicket(customerId: String, subject: String, description: String): String {
        val tickets = readTickets()
        if (readUsers().none { it.id == customerId }) return "(no user $customerId)"
        val stamp = now()
        val ticket = SupportTicket(
            id = nextTicketId(tickets),
            subject = subject,
            description = description,
            status = "new",
            priority = "normal",
            createdAt = stamp,
            updatedAt = stamp,
            customerId = customerId,
        )
        writeTickets(tickets + ticket)
        return "Created ${ticket.id} (status new) for $customerId"
    }

    /**
     * Developer action: set [id]'s [status] and [resolution], append a note, and persist.
     * Rejects an unknown id or a status outside [TICKET_STATUSES]. The caller (dev launch)
     * is the access gate — this method assumes the session is already authorized.
     */
    fun setTicketStatus(id: String, status: String, resolution: String): String {
        if (status !in TICKET_STATUSES) return "(invalid status '$status'; use one of ${TICKET_STATUSES.joinToString(", ")})"
        val tickets = readTickets()
        val target = tickets.firstOrNull { it.id == id } ?: return "(no ticket $id)"
        val stamp = now()
        val updated = target.copy(
            status = status,
            resolution = resolution.ifBlank { target.resolution },
            updatedAt = stamp,
            comments = target.comments + TicketComment("developer", stamp, "status → $status"),
        )
        writeTickets(tickets.map { if (it.id == id) updated else it })
        return "Ticket $id → $status"
    }

    private fun nextTicketId(tickets: List<SupportTicket>): String {
        val maxNum = tickets.mapNotNull { it.id.substringAfterLast('-').toIntOrNull() }.maxOrNull() ?: 0
        return "TICKET-${maxNum + 1}"
    }

    private fun readTickets(): List<SupportTicket> =
        JSON.decodeFromString(TICKETS_SERIALIZER, store.read(TICKETS_FILE))

    private fun readUsers(): List<SupportUser> =
        JSON.decodeFromString(USERS_SERIALIZER, store.read(USERS_FILE))

    private fun writeTickets(tickets: List<SupportTicket>) {
        store.write(TICKETS_FILE, PRETTY_JSON.encodeToString(TICKETS_SERIALIZER, tickets))
    }

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
        t.resolution?.takeIf { it.isNotBlank() }?.let { appendLine("Resolution: $it") }
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

    private fun formatUserSummary(u: SupportUser): String =
        "${u.id} ${u.name} <${u.email}> (tariff ${u.tariff})"

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

        /** The statuses [setTicketStatus] accepts. */
        val TICKET_STATUSES = listOf("new", "in_progress", "resolved", "wontfix")

        private val JSON = Json { ignoreUnknownKeys = true }
        private val PRETTY_JSON = Json { prettyPrint = true; encodeDefaults = true }
        private val TICKETS_SERIALIZER = kotlinx.serialization.builtins.ListSerializer(SupportTicket.serializer())
        private val USERS_SERIALIZER = kotlinx.serialization.builtins.ListSerializer(SupportUser.serializer())
    }
}

/**
 * Production [Store]: reads and writes `<root>/<name>` on disk. The impure edge — unwrapped
 * so tests can swap it for an in-memory map.
 */
class FileStore(private val root: String) : Store {
    override fun read(name: String): String =
        java.io.File(root, name).readText(Charsets.UTF_8)

    override fun write(name: String, text: String) {
        java.io.File(root, name).writeText(text, Charsets.UTF_8)
    }
}
