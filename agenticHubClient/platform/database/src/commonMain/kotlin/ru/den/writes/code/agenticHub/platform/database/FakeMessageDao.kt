package ru.den.writes.code.agenticHub.platform.database

/**
 * In-memory [MessageDao] for tests — reimplements the DAO's query semantics over
 * plain lists/maps (no SQLite, no temp file), so it runs on **every** target and
 * needs no Room. `internal` — only reachable via
 * [databaseTestModule][ru.den.writes.code.agenticHub.platform.database.di.databaseTestModule],
 * under the [MessageDao] interface.
 *
 * Insertion assigns strictly increasing ids (as Room's autogenerate does), so
 * list/filter order equals `ORDER BY id ASC`. Not thread-safe (tests are
 * single-threaded). Unlike `TestDb` (a real in-memory Room DB), this is a hand
 * fake — pick it for graph composition; pick `TestDb` when you want the real SQL.
 */
internal class FakeMessageDao : MessageDao {
    private val messages = mutableListOf<MessageEntity>()
    private val summaries = mutableMapOf<Pair<String, String>, SummaryEntity>()
    private val facts = mutableMapOf<Pair<String, String>, FactsEntity>()
    private var seq = 0L

    /** Rows of one (session, branch), in id-ascending (= insertion) order. */
    private fun rows(sessionId: String, branchId: String): List<MessageEntity> =
        messages.filter { it.sessionId == sessionId && it.branchId == branchId }

    override suspend fun all(sessionId: String, branchId: String): List<MessageEntity> =
        rows(sessionId, branchId)

    override suspend fun assistantMessages(sessionId: String, branchId: String): List<MessageEntity> =
        rows(sessionId, branchId).filter { it.role == "ASSISTANT" }

    override suspend fun tail(sessionId: String, n: Int, branchId: String): List<MessageEntity> =
        rows(sessionId, branchId).takeLast(n)

    override suspend fun insert(entity: MessageEntity) {
        messages += entity.copy(id = ++seq)
    }

    override suspend fun listSessions(): List<SessionSummary> =
        messages
            .groupBy { it.sessionId to it.branchId }
            .map { (key, group) -> Triple(key, group.size, group.minOf { it.id }) }
            .sortedBy { it.third }
            .map { (key, size, _) -> SessionSummary(key.first, key.second, size) }

    override suspend fun count(): Int = messages.size

    override suspend fun countSession(sessionId: String): Int =
        messages.count { it.sessionId == sessionId }

    override suspend fun clearAll() {
        messages.clear()
    }

    override suspend fun deleteSessionMessages(sessionId: String) {
        messages.removeAll { it.sessionId == sessionId }
    }

    override suspend fun getSummary(sessionId: String, branchId: String): SummaryEntity? =
        summaries[sessionId to branchId]

    override suspend fun upsertSummary(entity: SummaryEntity) {
        summaries[entity.sessionId to entity.branchId] = entity
    }

    override suspend fun clearAllSummaries() {
        summaries.clear()
    }

    override suspend fun deleteSessionSummaries(sessionId: String) {
        summaries.keys.removeAll { it.first == sessionId }
    }

    override suspend fun getFacts(sessionId: String, branchId: String): FactsEntity? =
        facts[sessionId to branchId]

    override suspend fun upsertFacts(entity: FactsEntity) {
        facts[entity.sessionId to entity.branchId] = entity
    }

    override suspend fun clearAllFacts() {
        facts.clear()
    }

    override suspend fun deleteSessionFacts(sessionId: String) {
        facts.keys.removeAll { it.first == sessionId }
    }

    override suspend fun branchesOf(sessionId: String): List<String> =
        messages
            .filter { it.sessionId == sessionId }
            .groupBy { it.branchId }
            .map { (branch, group) -> branch to group.minOf { it.id } }
            .sortedBy { it.second }
            .map { it.first }

    override suspend fun copyBranchMessages(sessionId: String, fromBranch: String, toBranch: String) {
        rows(sessionId, fromBranch).forEach { src ->
            messages += src.copy(id = ++seq, branchId = toBranch)
        }
    }

    override suspend fun deleteBranchMessages(sessionId: String, branchId: String) {
        messages.removeAll { it.sessionId == sessionId && it.branchId == branchId }
    }

    override suspend fun deleteBranchSummary(sessionId: String, branchId: String) {
        summaries.remove(sessionId to branchId)
    }

    override suspend fun deleteBranchFacts(sessionId: String, branchId: String) {
        facts.remove(sessionId to branchId)
    }
}
