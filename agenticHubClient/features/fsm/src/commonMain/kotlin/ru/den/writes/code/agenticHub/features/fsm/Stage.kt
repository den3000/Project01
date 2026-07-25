package ru.den.writes.code.agenticHub.features.fsm

/**
 * One stage in a task's finite-state machine. A task moves
 * `clarification → planning → execution → validation → done`, with single
 * step-backs allowed. The stage is the piece of working memory that turns a
 * loose chat into a controlled process: it is injected into every turn (so the
 * model knows which phase it is in) and persisted (so a paused task resumes
 * where it stopped).
 *
 * [keyword] is the lowercase token used on the wire, in `## Stage` markdown, and
 * in the `[[stage:<keyword>]]` signal the model emits to advance. [displayName]
 * is the human label for status output. [expectedAction] is the per-stage
 * instruction injected into the prompt — it is what makes the agent behave
 * differently per phase instead of attempting the whole task at once.
 */
enum class Stage(
    val keyword: String,
    val displayName: String,
    val expectedAction: String,
) {
    CLARIFICATION(
        "clarification",
        "Clarification",
        "Confirm the goal, constraints and definition of done. If they are already clear, or the " +
            "user asked you to proceed on your own, state your working assumptions and move on — do " +
            "not demand input that will not come. Ask only when genuinely blocked. Do not plan or " +
            "write the solution yet.",
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
        val INITIAL: Stage = CLARIFICATION

        /** Map a [keyword] (case-insensitively) back to a stage; null if unknown. */
        fun byKeyword(token: String): Stage? {
            val needle = token.trim().lowercase()
            return entries.firstOrNull { it.keyword == needle }
        }
    }
}
