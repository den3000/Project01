package ru.den.writes.code.agenticHub.features.lifecycle.start

import ru.den.writes.code.agenticHub.features.memory.SessionStats
import ru.den.writes.code.agenticHub.features.lifecycle.command.MemoryAction
import ru.den.writes.code.agenticHub.features.lifecycle.command.StartCommand
import ru.den.writes.code.agenticHub.platform.database.AppDatabase
import ru.den.writes.code.agenticHub.platform.database.DEFAULT_BRANCH
import ru.den.writes.code.agenticHub.platform.database.MessageEntity
import ru.den.writes.code.agenticHub.features.memory.db.seedFrom
import ru.den.writes.code.agenticHub.features.memory.FileMemoryStore
import ru.den.writes.code.agenticHub.features.memory.MemoryProvider
import ru.den.writes.code.agenticHub.features.memory.MemoryStore
import ru.den.writes.code.agenticHub.platform.filesystem.LocalFileSystem
import ru.den.writes.code.agenticHub.features.llm.Usage
import ru.den.writes.code.agenticHub.features.memory.ProfileSection
import ru.den.writes.code.agenticHub.features.memory.TaskNotes
import ru.den.writes.code.agenticHub.features.memory.TaskStage
import ru.den.writes.code.agenticHub.features.llm.pricing.PricingRegistry

/** Which stream an [AdminNotice] belongs on when the composition root prints it. */
public enum class OutputStream { STDOUT, STDERR }

/** One line of admin-command output plus the stream it goes on. */
public data class AdminNotice(val stream: OutputStream, val text: String)

private fun out(text: String) = AdminNotice(OutputStream.STDOUT, text)
private fun err(text: String) = AdminNotice(OutputStream.STDERR, text)

/**
 * Admin-command logic (list / clean / inflate / memory ops) — free of I/O: each op
 * returns the lines it would emit (tagged stdout/stderr) instead of printing them,
 * so the composition root owns the actual output (and the ops stay testable). No
 * LLM, no session runtime; owns only the [db].
 */
public class AdminOps(
    private val db: AppDatabase,
    private val fs: LocalFileSystem,
) {

    /** Wipe every messages / summaries / facts row. */
    public suspend fun cleanHistory(): List<AdminNotice> {
        val before = db.messageDao().count()
        db.messageDao().clearAll()
        db.messageDao().clearAllSummaries()
        db.messageDao().clearAllFacts()
        return listOf(out("Cleared $before messages across all sessions (and any saved summaries / facts)."))
    }

    /** Wipe one session's rows by name — the per-session twin of [cleanHistory]. */
    public suspend fun cleanSession(sessionId: String): List<AdminNotice> {
        val dao = db.messageDao()
        val before = dao.countSession(sessionId)
        dao.deleteSessionMessages(sessionId)
        dao.deleteSessionSummaries(sessionId)
        dao.deleteSessionFacts(sessionId)
        return listOf(out("Cleared $before messages from session '$sessionId' (and any saved summary / facts)."))
    }

    /**
     * Duplicates the last N rows of the given session in-place. No LLM, no network —
     * pure DB ALTER. Copies carry just `text` + `role`; `model_id` and token counts
     * are cleared so [SessionStats] doesn't double-count already-billed usage.
     */
    public suspend fun inflateSession(command: StartCommand.InflateSession): List<AdminNotice> {
        val dao = db.messageDao()
        val tail = dao.tail(command.sessionId, command.n)
        if (tail.isEmpty()) {
            return listOf(out("[inflate] session ${command.sessionId} has no messages — nothing to copy."))
        }
        tail.forEach { row ->
            dao.insert(
                MessageEntity(
                    sessionId = command.sessionId,
                    role = row.role,
                    text = row.text,
                    // Token / pricing columns left NULL on the copies: synthetic ballast.
                )
            )
        }
        val total = dao.all(command.sessionId).size
        return listOf(out("[inflate] copied ${tail.size} message(s) into session ${command.sessionId}; total now $total."))
    }

    /**
     * Cross-session summary for `ListSessions`. One row per (session, branch), with
     * message count + lifetime token/cost totals reconstructed from stored ASSISTANT
     * rows via [SessionStats], plus `compressed(...)` / `facts(...)` overhead segments.
     */
    public suspend fun listSessions(): List<AdminNotice> {
        val dao = db.messageDao()
        val sessions = dao.listSessions()
        if (sessions.isEmpty()) return listOf(out("(no sessions)"))

        fun overheadOf(modelId: String?, prompt: Int?, output: Int?, thoughts: Int?, total: Int?): Pair<Int, Double> {
            val usage = Usage(
                promptTokens = prompt ?: 0,
                outputTokens = output ?: 0,
                thoughtsTokens = thoughts ?: 0,
                totalTokens = total ?: 0,
            )
            val cost = modelId?.let(PricingRegistry::lookup)?.let { PricingRegistry.cost(usage, it) } ?: 0.0
            return usage.totalTokens to cost
        }

        return sessions.map { summary ->
            val stats = SessionStats().apply {
                seedFrom(dao.assistantMessages(summary.sessionId, summary.branchId), PricingRegistry::lookup)
            }
            val summaryRow = dao.getSummary(summary.sessionId, summary.branchId)
            val factsRow = dao.getFacts(summary.sessionId, summary.branchId)
            val (sumTok, sumCost) = summaryRow
                ?.let { overheadOf(it.modelId, it.promptTokens, it.outputTokens, it.thoughtsTokens, it.totalTokens) }
                ?: (0 to 0.0)
            val (factTok, factCost) = factsRow
                ?.let { overheadOf(it.modelId, it.promptTokens, it.outputTokens, it.thoughtsTokens, it.totalTokens) }
                ?: (0 to 0.0)
            out(
                formatSessionLine(
                    sessionId = summary.sessionId,
                    branchId = summary.branchId,
                    messageCount = summary.count,
                    totalTokens = stats.totalTokens,
                    costUsd = stats.totalCostUsd,
                    coveredCount = summaryRow?.coveredCount,
                    overheadTokens = sumTok,
                    overheadCostUsd = sumCost,
                    factsPresent = factsRow != null,
                    factsOverheadTokens = factTok,
                    factsOverheadCostUsd = factCost,
                )
            )
        }
    }

    /**
     * Run a memory invocation against the on-disk memory root [memoryRoot]. Pure disk
     * operation — no LLM, no session, no DB. Returns short status lines (errors tagged
     * stderr). [FileMemoryStore] creates [memoryRoot] on construction.
     */
    public fun handleMemoryCommand(action: MemoryAction, memoryRoot: String): List<AdminNotice> {
        val store = FileMemoryStore(memoryRoot, fs)
        return when (action) {
            is MemoryAction.Show ->
                // Temporary provider in PREAMBLE mode for describe() — no task is active
                // from the CLI; the dormant snapshot of every layer.
                listOf(out(MemoryProvider(store, initialTaskId = null).describe()))
            is MemoryAction.AddProfileItem -> {
                val updated = store.addProfileItem(action.section, action.text)
                val count = updated.items(action.section).size
                listOf(out("[memory] profile.${action.section.keyword} += \"${action.text}\" ($count item(s) total)"))
            }
            is MemoryAction.ClearProfileSection -> {
                store.clearProfileSection(action.section)
                listOf(out("[memory] profile.${action.section.keyword} cleared"))
            }
            is MemoryAction.ClearProfile -> {
                store.clearProfile()
                listOf(out("[memory] profile cleared"))
            }
            is MemoryAction.ListProfiles -> {
                val names = store.listProfileNames()
                if (names.isEmpty()) listOf(out("[memory] no named profiles"))
                else buildList {
                    add(out("[memory] profiles:"))
                    names.forEach { add(out("  - $it")) }
                }
            }
            is MemoryAction.ShowProfile -> {
                val data = store.loadNamedProfile(action.name)
                if (data == null) {
                    listOf(err("[memory] profile '${action.name}' is empty or absent"))
                } else buildList {
                    add(out("[profile:${action.name}]"))
                    data.freeText?.takeIf { it.isNotBlank() }?.let { add(out(it.trim())) }
                    for (section in ProfileSection.entries) {
                        val items = data.items(section)
                        if (items.isEmpty()) continue
                        add(out("${section.keyword}: ${items.joinToString(", ")}"))
                    }
                }
            }
            is MemoryAction.TouchProfile -> {
                store.touchNamedProfile(action.name)
                listOf(out("[memory] profile '${action.name}' ready under $memoryRoot/${FileMemoryStore.PROFILES_DIR}/${action.name}.md"))
            }
            is MemoryAction.AddNamedProfileItem -> {
                val updated = store.addNamedProfileItem(action.name, action.section, action.text)
                val count = updated.items(action.section).size
                listOf(out("[memory] profile.${action.name}.${action.section.keyword} += \"${action.text}\" ($count item(s) total)"))
            }
            is MemoryAction.ClearNamedProfileSection -> {
                store.clearNamedProfileSection(action.name, action.section)
                listOf(out("[memory] profile.${action.name}.${action.section.keyword} cleared"))
            }
            is MemoryAction.ClearNamedProfile -> {
                val removed = store.clearNamedProfile(action.name)
                if (removed) listOf(out("[memory] profile '${action.name}' removed"))
                else listOf(err("[memory] no profile named '${action.name}'"))
            }
            is MemoryAction.ClearAllProfiles -> {
                val n = store.clearAllProfiles()
                listOf(out("[memory] all profiles cleared ($n named + unnamed)"))
            }
            is MemoryAction.AddRule -> {
                val rule = store.addRule(action.text)
                listOf(out("[memory] rule ${rule.id} added"))
            }
            is MemoryAction.RemoveRule -> {
                val removed = store.removeRule(action.id)
                if (removed) listOf(out("[memory] rule ${action.id} removed"))
                else listOf(err("[memory] no rule with id '${action.id}'"))
            }
            is MemoryAction.ClearRules -> {
                val n = store.clearRules()
                listOf(out("[memory] cleared $n rule(s)"))
            }
            is MemoryAction.SetTask -> {
                // Touch-create so a subsequent show (and the next chat with the task)
                // sees a file. A new task starts at the initial stage.
                if (store.loadTask(action.taskId) == null) {
                    store.saveTask(TaskNotes(taskId = action.taskId, stage = TaskStage.INITIAL))
                }
                listOf(out("[memory] task '${action.taskId}' ready under $memoryRoot/${FileMemoryStore.TASKS_DIR}/${action.taskId}.md"))
            }
            is MemoryAction.PauseTask -> setTaskPaused(store, action.taskId, paused = true)
            is MemoryAction.ResumeTask -> setTaskPaused(store, action.taskId, paused = false)
            is MemoryAction.DeleteTask -> {
                val removed = store.deleteTask(action.taskId)
                if (removed) listOf(out("[memory] task '${action.taskId}' deleted"))
                else listOf(err("[memory] no task '${action.taskId}'"))
            }
            is MemoryAction.ClearTasks -> {
                val n = store.clearTasks()
                listOf(out("[memory] cleared $n task(s)"))
            }
        }
    }

    /**
     * Pure-disk pause/resume. Loads (or touch-creates at the initial stage) the task,
     * flips its `paused` flag, writes it back. A paused task holds its stage.
     */
    private fun setTaskPaused(store: MemoryStore, taskId: String, paused: Boolean): List<AdminNotice> {
        val task = store.loadTask(taskId) ?: TaskNotes(taskId = taskId, stage = TaskStage.INITIAL)
        store.saveTask(task.copy(paused = paused))
        val word = if (paused) "paused" else "resumed"
        return listOf(out("[memory] task '$taskId' $word (stage ${task.stage?.keyword ?: "(none)"})"))
    }
}

/**
 * Render one `ListSessions` row for a (session, branch). The branch is shown as
 * `session/branch` unless it's the default `main`. A `compressed(...)` segment is
 * appended when [coveredCount] is non-null (rolling summary) and a `facts(...)`
 * segment when [factsPresent]. Overhead figures are NOT folded into [totalTokens]
 * / [costUsd] — those stay the billed exchange totals; the overhead is shown
 * alongside so the price of each strategy is visible.
 */
public fun formatSessionLine(
    sessionId: String,
    messageCount: Int,
    totalTokens: Int,
    costUsd: Double,
    branchId: String = DEFAULT_BRANCH,
    coveredCount: Int? = null,
    overheadTokens: Int = 0,
    overheadCostUsd: Double = 0.0,
    factsPresent: Boolean = false,
    factsOverheadTokens: Int = 0,
    factsOverheadCostUsd: Double = 0.0,
): String {
    val label = if (branchId == DEFAULT_BRANCH) sessionId else "$sessionId/$branchId"
    var line = "$label\t$messageCount messages" +
        "\ttotal_tokens=$totalTokens" +
        "\tcost=\$${"%.5f".format(costUsd)}"
    if (coveredCount != null) {
        line += "\tcompressed(covered=$coveredCount/$messageCount" +
            ", overhead=${overheadTokens}tok \$${"%.5f".format(overheadCostUsd)})"
    }
    if (factsPresent) {
        line += "\tfacts(overhead=${factsOverheadTokens}tok \$${"%.5f".format(factsOverheadCostUsd)})"
    }
    return line
}
