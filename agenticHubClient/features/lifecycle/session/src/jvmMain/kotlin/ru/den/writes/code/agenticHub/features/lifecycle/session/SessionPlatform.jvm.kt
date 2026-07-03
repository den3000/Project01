package ru.den.writes.code.agenticHub.features.lifecycle.session

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual fun nowMillis(): Long = System.currentTimeMillis()

internal actual fun schedulerDispatcher(): CoroutineDispatcher = Dispatchers.IO
