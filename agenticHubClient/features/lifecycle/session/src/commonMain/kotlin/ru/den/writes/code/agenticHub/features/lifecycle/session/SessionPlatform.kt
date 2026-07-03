package ru.den.writes.code.agenticHub.features.lifecycle.session

import kotlinx.coroutines.CoroutineDispatcher

/**
 * The two platform primitives the scheduler wiring needs, kept behind
 * `expect`/`actual` so [SessionAssembly] and its loops stay in `commonMain`.
 * JVM is real; iOS/Android are `TODO()` until they grow a real session runtime.
 */

/** Wall-clock epoch millis fed to the [ru.den.writes.code.agenticHub.scheduling.SchedulerEngine]. */
internal expect fun nowMillis(): Long

/** Dispatcher for the scheduler ticker/reporter — IO, since a task handler may block. */
internal expect fun schedulerDispatcher(): CoroutineDispatcher
