package ru.den.writes.code.project01.cliJvm.db

import ru.den.writes.code.project01.cliJvm.Message
import ru.den.writes.code.project01.cliJvm.Role

/**
 * Conversation-history persistence for one session.
 *
 * Thin wrapper over [MessageDao] that hides Room types from the rest
 * of the codebase. [Agent] depends only on this — it never sees
 * [MessageEntity] or DAO directly. Translation between the neutral
 * [Message] / [Role] (which cross the [ru.den.writes.code.project01.cliJvm.LlmApi]
 * boundary) and the persisted [MessageEntity] (string `role` column,
 * `session_id` discriminator) lives here.
 *
 * The store is bound to one [sessionId] at construction; all
 * load/append operations are implicitly scoped to it, so callers
 * don't need to thread the session id through every call.
 */
internal class HistoryStore(
    private val dao: MessageDao,
    private val sessionId: String,
) {
    /** Load every message previously persisted under this session id. */
    suspend fun load(): List<Message> = dao.all(sessionId).map {
        Message(role = Role.valueOf(it.role), text = it.text)
    }

    /**
     * Append one message to this session's history. Cheap — one row
     * insert per call. Used twice per agent turn (user message + model
     * reply) and only after a successful exchange, so a crashed turn
     * never leaves a half-conversation in the table.
     */
    suspend fun append(message: Message) {
        dao.insert(
            MessageEntity(
                sessionId = sessionId,
                role = message.role.name,
                text = message.text,
            )
        )
    }
}
