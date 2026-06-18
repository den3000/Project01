package ru.den.writes.code.project01.shared.memory

/**
 * One stage in a task's finite-state machine. A task moves
 * `clarification → planning → execution → validation → done`, with single
 * step-backs allowed (see [TaskStateMachine.allowedNext]). The stage is the
 * piece of working memory that turns a loose chat into a controlled process:
 * it's injected into every turn (so the model knows which phase it is in) and
 * persisted to `tasks/<id>.md` (so a paused task resumes where it stopped).
 *
 * [keyword] is the lowercase token used on the wire, in `## Stage` markdown,
 * and in the `[[stage:<keyword>]]` signal the model emits to advance.
 * [displayName] is the human label for status output. [expectedAction] is the
 * per-stage instruction injected into the prompt — it's what makes the agent
 * behave differently per phase instead of attempting the whole task at once.
 */
enum class TaskStage(
    val keyword: String,
    val displayName: String,
    val expectedAction: String,
) {
    CLARIFICATION(
        "clarification",
        "Clarification",
        "Gather and confirm the requirements, constraints and definition of done. " +
            "Ask before assuming. Do not plan or write the solution yet.",
    ),
    PLANNING(
        "planning",
        "Planning",
        "Produce a concrete, reviewable plan. Do not start the work until the plan is settled.",
    ),
    EXECUTION(
        "execution",
        "Execution",
        "Carry out the agreed plan. Don't re-plan or widen the scope; " +
            "if the plan turns out wrong, step back to planning.",
    ),
    VALIDATION(
        "validation",
        "Validation",
        "Check the result against the goal and the constraints. " +
            "Report pass/fail and exactly what to fix.",
    ),
    DONE(
        "done",
        "Done",
        "The task is complete. Do no further work unless it is reopened.",
    ),
    ;

    companion object {
        /** The stage every new task starts in. */
        val INITIAL: TaskStage = CLARIFICATION

        /** Map a [keyword] (case-insensitively) back to a stage; null if unknown. */
        fun byKeyword(token: String): TaskStage? {
            val needle = token.trim().lowercase()
            return entries.firstOrNull { it.keyword == needle }
        }
    }
}

/**
 * The allowed-transition table plus the helpers that drive auto-advance.
 * Pure (no I/O) so it ports to every target and is trivial to unit-test — same
 * shape as `HistoryCompressor` / `FactsExtractor`.
 *
 * Transitions are validated in code, not just described in the prompt: an LLM
 * will happily skip a stage if asked, so the hard "no `planning → done`"
 * guarantee has to live here. The model only ever *proposes* the next stage
 * (via [parseStageSignal]); [canTransition] decides whether to honour it.
 */
object TaskStateMachine {
    /**
     * Stages reachable from [stage] in one step: forward, plus a single step
     * back to revisit the prior phase. [TaskStage.DONE] is terminal (empty
     * set) — a finished task isn't reopened automatically.
     */
    fun allowedNext(stage: TaskStage): Set<TaskStage> = when (stage) {
        TaskStage.CLARIFICATION -> setOf(TaskStage.PLANNING)
        TaskStage.PLANNING -> setOf(TaskStage.EXECUTION, TaskStage.CLARIFICATION)
        TaskStage.EXECUTION -> setOf(TaskStage.VALIDATION, TaskStage.PLANNING)
        TaskStage.VALIDATION -> setOf(TaskStage.DONE, TaskStage.EXECUTION)
        TaskStage.DONE -> emptySet()
    }

    /**
     * Whether moving [from] → [to] is permitted. A null [from] (a task with
     * no stage yet — a legacy or hand-edited file) accepts any [to] as
     * initialization: there's no prior stage to violate. Once a stage is set,
     * only [allowedNext] entries pass.
     */
    fun canTransition(from: TaskStage?, to: TaskStage): Boolean =
        if (from == null) true else to in allowedNext(from)

    /**
     * Extract the stage the model wants to move to from its [reply]. The model
     * signals an advance with a `[[stage:<keyword>]]` marker; we take the LAST
     * one (its final decision, if it reconsidered mid-reply) and map it via
     * [TaskStage.byKeyword]. Returns null when there's no marker or the keyword
     * is unknown — the caller then leaves the stage untouched.
     *
     * Read-only: the [reply] is never mutated, so the marker stays visible in
     * the printed / persisted text.
     */
    fun parseStageSignal(reply: String): TaskStage? {
        val match = STAGE_SIGNAL.findAll(reply).lastOrNull() ?: return null
        return TaskStage.byKeyword(match.groupValues[1])
    }

    /**
     * `[[stage:<keyword>]]` — case-insensitive, tolerant of inner spaces
     * (`[[ stage : execution ]]`). The keyword is captured as letters only;
     * resolving it to a real stage is [TaskStage.byKeyword]'s job.
     */
    private val STAGE_SIGNAL = Regex("""\[\[\s*stage\s*:\s*([a-zA-Z]+)\s*]]""", RegexOption.IGNORE_CASE)
}
