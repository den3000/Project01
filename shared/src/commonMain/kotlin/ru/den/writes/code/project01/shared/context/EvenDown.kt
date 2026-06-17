package ru.den.writes.code.project01.shared.context

/**
 * Snap a count down to the nearest even number, floored at 0.
 *
 * Shared by the context strategies that keep a USER-first / even tail
 * (the sliding window and [HistoryCompressor]) — both rely on the parity
 * invariant so the wire history stays role-alternating and USER-first.
 */
fun evenDown(n: Int): Int = (if (n < 0) 0 else n).let { it - it % 2 }
