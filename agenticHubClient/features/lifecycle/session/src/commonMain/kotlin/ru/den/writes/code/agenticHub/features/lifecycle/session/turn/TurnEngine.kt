package ru.den.writes.code.agenticHub.features.lifecycle.session.turn

import ru.den.writes.code.agenticHub.features.lifecycle.command.StartCommand
import ru.den.writes.code.agenticHub.features.llm.GenerationParams
import ru.den.writes.code.agenticHub.features.llm.LlmApi

/**
 * One exchange with the model, computed without any direct I/O: build the wire,
 * call the agent, persist what stands, and report the outcome as a [TurnResult].
 * A view renders the result; the view-model orchestrates the loop around it.
 *
 * The whole surface is [turn] — everything else an implementation needs is its
 * own business. That is deliberate: the task FSM is being moved out of the engine
 * into `features:fsm`, and a one-method seam lets both engines exist side by side
 * and be measured against the same tests and the same prompts.
 *
 * What an implementation may NOT do: print, sleep, or decide anything the caller
 * has to act on outside the turn. Restarting a task, for instance, means branching
 * the conversation and rebuilding the engine — an engine cannot do that to itself,
 * so it belongs above this interface, not behind it.
 */
public interface TurnEngine {

    /** Run one turn for [prompt] and return what happened. */
    public suspend fun turn(prompt: String): TurnResult
}

/**
 * Lift the generation-related flags from the parsed CLI into the neutral
 * [GenerationParams] that crosses the [LlmApi] boundary. `-prompt` (the
 * per-turn payload) and `-model` (configured into the concrete [LlmApi]) are
 * not part of this. Lives on the [StartCommand.SessionInitialState] super-type so RunChat and
 * RunOneShot share the same conversion.
 *
 * Next to the interface rather than inside an implementation: every engine needs
 * the same conversion, and `apps:cliJvmApp` calls it directly when it builds the
 * routed agents.
 */
public fun StartCommand.SessionInitialState.toGenerationParams(): GenerationParams =
    GenerationParams(
        maxTokens = maxTokens,
        stopSequences = stopSequences,
        endSequence = endSequence,
        temperature = temperature,
        topP = topP,
        seed = seed,
        contextWindow = contextWindow,
    )
