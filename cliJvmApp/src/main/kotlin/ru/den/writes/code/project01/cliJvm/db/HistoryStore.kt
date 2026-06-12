package ru.den.writes.code.project01.cliJvm.db

import ru.den.writes.code.project01.cliJvm.Message
import ru.den.writes.code.project01.cliJvm.Role

/**
 * Conversation-history persistence for one session, with an in-memory
 * cache so callers don't have to keep their own copy.
 *
 * Thin wrapper over [MessageDao] that hides Room types from the rest
 * of the codebase. [Agent] depends only on this — it never sees
 * [MessageEntity] or DAO directly. Translation between the neutral
 * [Message] / [Role] (which cross the [ru.den.writes.code.project01.cliJvm.LlmApi]
 * boundary) and the persisted [MessageEntity] (string `role` column,
 * `session_id` discriminator) lives here.
 *
 * The store is bound to one [sessionId] at construction; all
 * load/append operations are implicitly scoped to it.
 */
internal class HistoryStore(
    private val dao: MessageDao,
    private val sessionId: String,
) {
    /**
     * Backing cache so we don't hit SQLite on every turn just to rebuild
     * the message list. Kept in sync with the DB: [load] replaces it,
     * [append] grows it after a successful insert.
     */
    private val cache = mutableListOf<Message>()

    /**
     * Live view of currently-loaded messages. Returns the same backing
     * list across calls, so callers see new entries automatically after
     * [append]; defensive copies are the caller's responsibility if they
     * need a snapshot.
     */
    val messages: List<Message> get() = cache

    /**
     * Hydrate the cache from the DB. Call once on startup. Subsequent
     * calls would refresh from disk, but in this app's single-process
     * model there's no scenario where the DB diverges from the cache
     * after init.
     */
    suspend fun load() {
        val loaded = dao.all(sessionId).map {
            Message(role = Role.valueOf(it.role), text = it.text)
        }
        cache.clear()
        cache += loaded
    }

    /**
     * Append one message to this session's history. Persists to DB first,
     * then mirrors into the cache — that way a failed insert (throws)
     * doesn't leave the in-memory view ahead of the DB.
     *
     * Used twice per agent turn (user message + model reply) and only
     * after a successful exchange, so a crashed turn never leaves a
     * half-conversation on disk.
     */
    suspend fun append(message: Message) {
        dao.insert(
            MessageEntity(
                sessionId = sessionId,
                role = message.role.name,
                text = message.text,
            )
        )
        cache += message
    }
}
